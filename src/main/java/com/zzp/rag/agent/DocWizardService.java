package com.zzp.rag.agent;

import com.zzp.rag.retrieval.RetrievalPipeline;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 对话式文档生成向导（多轮状态机，通用"多轮收集"机制的第一用例）。
 * <p>
 * 状态按 (会话+用户) 双维度隔离（群聊多人互不干扰），10 分钟超时自动清理。
 * 流程：触发 → 收集（自由文本槽位解析，只追问缺失项）→ 确认回显（默认值标注）
 * → 生成全文 → 预览（修改循环）→ 定稿出文件。
 * <p>
 * 槽位：类型(默认通用)/标题(必填)/要点/语气(默认正式)/格式(默认docx)/落款(默认部门)。
 * 知识库注入：要点或修改指令引《文档名》→ 按员工权限检索注入。
 * 纯文本交互，无任何图标。
 */
@Slf4j
@Service
public class DocWizardService {

    // ==================== 状态与槽位 ====================

    public enum Stage { COLLECT, CONFIRM, PREVIEW }

    /** 向导状态：全字段可变（单线程访问：Feishu 按会话串行、Web 会话天然串行） */
    public static class State {
        Stage stage = Stage.COLLECT;
        String docType = "通用";
        String title = "";
        String points = "";
        String tone = "正式";
        String format = "docx";
        String issuer = "";
        String content = "";      // 生成/修改后的全文 markdown
        long lastActiveAt = System.currentTimeMillis();
    }

    public record WizardResult(String reply, String route, String pendingFileName, byte[] pendingFile) {}

    /** 表单一次性提交结果（飞书表单卡片）：docId 供 H5 编辑器加载，cloudUrl 失败为 null */
    public record FormResult(String docId, String content, String cloudUrl) {}

    /** 工作台编辑器可加载的生成文档（内存存储，重启失效；仅供生成后编辑跳转用） */
    public record StoredDoc(String title, String content) {}

    /** 免责脚注（所有出文渠道共用） */
    public static final String FOOTER = "---\n\n本文引用资料以知识库原文为准，由 AI 辅助生成，请人工核对后发布。";

    private static final long TTL_MS = 10 * 60_000L;

    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();
    /** 生成后编辑跳转的内容存档（docId → 标题+正文） */
    private final ConcurrentHashMap<String, StoredDoc> studioDocs = new ConcurrentHashMap<>();
    private final DocAssistantService docAssistant;
    private final RetrievalPipeline retrievalPipeline;
    private final com.zzp.rag.feishu.FeishuClient feishuClient;

    @Value("${rag.doc.default-issuer:}")
    private String defaultIssuer;

    /** 员工部门（落款默认值来源；由编排器在员工解析后注入） */
    private final ThreadLocal<String> deptHint = new ThreadLocal<>();

    /** 发起用户 open_id（云文档编辑者授权用；由飞书桥在编排前注入） */
    private final ThreadLocal<String> editorHint = new ThreadLocal<>();

    /** 定稿 docx 直出飞书云文档（在线协作）；false = 文件消息 */
    @Value("${rag.doc.cloud-doc:true}")
    private boolean cloudDocEnabled;

    public DocWizardService(DocAssistantService docAssistant, RetrievalPipeline retrievalPipeline,
                            com.zzp.rag.feishu.FeishuClient feishuClient) {
        this.docAssistant = docAssistant;
        this.retrievalPipeline = retrievalPipeline;
        this.feishuClient = feishuClient;
    }

    public void setDeptHint(String dept) {
        if (dept != null && !dept.isBlank()) {
            deptHint.set(dept);
        }
    }

    public void clearDeptHint() {
        deptHint.remove();
    }

    /** 云文档编辑者授权：发起用户 open_id（飞书桥在编排前注入） */
    public void setEditorHint(String openId) {
        if (openId != null && !openId.isBlank()) {
            editorHint.set(openId);
        }
    }

    public void clearEditorHint() {
        editorHint.remove();
    }

    // ==================== 触发与取消 ====================

    private static final Pattern TRIGGER = Pattern.compile(
            "生成.{0,6}文档|写.{0,4}(文档|通知|报告|方案|周报|纪要)|起草|帮我写|拟一份|写个通知|写个报告");

    private static final Pattern CANCEL = Pattern.compile("^(取消|算了|不做了|不要了|退出)[。！!？?\\s]*$");

    /** 是否触发向导（已有进行中状态也算） */
    public boolean isActive(String key) {
        State s = states.get(key);
        if (s == null) {
            return false;
        }
        if (System.currentTimeMillis() - s.lastActiveAt > TTL_MS) {
            states.remove(key);
            return false;
        }
        return true;
    }

    /** 本轮消息是否触发向导（新建 or 已有进行中） */
    public boolean shouldHandle(String key, String query) {
        if (isActive(key)) {
            return true;   // 进行中的向导：所有输入都归向导（取消词在 handle 里处理）
        }
        return query != null && TRIGGER.matcher(query).find();
    }

    /** 是否为全新触发（无进行中状态 + 命中触发词）——飞书桥据此弹表单卡片，替代文字追问 */
    public boolean isFreshTrigger(String key, String query) {
        return !isActive(key) && query != null && TRIGGER.matcher(query).find();
    }

    /** 表单一次性提交：生成全文 → 云文档直出（发起者授编辑权）→ 注册预览状态（后续文本消息可继续修改/定稿） */
    public FormResult submitForm(String key, String docType, String title, String points,
                                 String tone, String issuer, String editorOpenId) {
        String content = docAssistant.generateContent(docType, title, points, tone, issuer);
        String url = publishCloudDoc(title, content, editorOpenId);
        State s = new State();
        s.stage = Stage.PREVIEW;
        s.docType = docType == null || docType.isBlank() ? "通用" : docType;
        s.title = title;
        s.points = points == null ? "" : points;
        s.tone = tone == null || tone.isBlank() ? "正式" : tone;
        s.issuer = issuer == null ? "" : issuer;
        s.content = content;
        states.put(key, s);
        // 存档供 H5 编辑器加载（生成后编辑跳转）；上限 100 份，超出淘汰最旧
        String docId = java.util.UUID.randomUUID().toString();
        studioDocs.put(docId, new StoredDoc(title, content));
        log.info("[文档向导] 表单生成存档 docId={}（供编辑器加载）", docId);
        if (studioDocs.size() > 100) {
            String oldest = studioDocs.keySet().iterator().next();
            studioDocs.remove(oldest);
        }
        return new FormResult(docId, content, url);
    }

    /** 工作台编辑器按 docId 取生成内容（无则 null） */
    public StoredDoc getStudioDoc(String docId) {
        return docId == null ? null : studioDocs.get(docId);
    }

    /** 云文档直出：创建 + 写入（标题去重 + 缩进排版）+ 落款右对齐 + 组织内可读 + 发起者编辑权 → 链接；失败返回 null */
    public String publishCloudDoc(String title, String content) {
        return publishCloudDoc(title, content, editorHint.get());
    }

    /** 同上，显式指定编辑者 open_id（表单提交路径）；null 则仅组织内可读 */
    public String publishCloudDoc(String title, String content, String editorOpenId) {
        try {
            String docId = feishuClient.createCloudDoc(title);
            if (docId == null) {
                return null;
            }
            java.util.List<String> blockIds = feishuClient.writeCloudDocBlocks(docId,
                    toBlocksForExport(content + "\n\n" + FOOTER, title));
            if (blockIds == null || blockIds.isEmpty()) {
                return null;
            }
            for (int idx : signoffIndexes(content)) {
                if (idx >= 0 && idx < blockIds.size()) {
                    feishuClient.updateBlockAlign(docId, blockIds.get(idx), 3);
                }
            }
            feishuClient.makeCloudDocOrgReadable(docId);
            if (editorOpenId != null && !editorOpenId.isBlank()) {
                feishuClient.addDocEditor(docId, editorOpenId);
            }
            return feishuClient.cloudDocUrl(docId);
        } catch (Exception e) {
            log.warn("[文档向导] 云文档直出失败: {}", e.getMessage());
            return null;
        }
    }

    /** 云文档不可用时的 docx 文件回退字节 */
    public byte[] toDocxFile(String title, String content) {
        return docAssistant.toFile("docx", title, content, FOOTER);
    }

    // ==================== 主处理入口 ====================

    /** 处理一轮向导输入 → 回复（route=DOC_WIZARD；定稿时携带文件） */
    public WizardResult handle(String key, String query, String scopeExpr, Long employeeId) {
        try {
            if (CANCEL.matcher(query == null ? "" : query.strip()).find()) {
                states.remove(key);
                return new WizardResult("已取消文档生成。有需要随时再找我。", "DOC_WIZARD", null, null);
            }
            State s = states.get(key);
            if (s == null) {
                s = new State();
                s.issuer = resolveIssuer(employeeId);
                states.put(key, s);
                return new WizardResult(
                        "好的，我来帮你生成文档。\n"
                                + "请告诉我【标题】和【内容要点】（要点里可以引用知识库文档，"
                                + "如「参考《员工年休假管理办法》」）。\n"
                                + "类型、语气、格式、落款可选：直接说即可（如「通知，正式，pdf，落款行政部」），"
                                + "不说则用默认值（通用文档、正式语气、docx、落款按你的部门自动取）。",
                        "DOC_WIZARD", null, null);
            }
            s.lastActiveAt = System.currentTimeMillis();
            return switch (s.stage) {
                case COLLECT -> collect(s, query, scopeExpr);
                case CONFIRM -> confirm(s, query, scopeExpr);
                case PREVIEW -> preview(s, key, query, scopeExpr);
            };
        } catch (Exception e) {
            log.error("[文档向导] 处理失败: {}", e.getMessage(), e);
            states.remove(key);
            return new WizardResult("生成过程出错，请重新发起。", "DOC_WIZARD", null, null);
        }
    }

    // ==================== 各阶段 ====================

    /** 收集：自由文本解析槽位 → 缺失追问 or 进确认 */
    private WizardResult collect(State s, String query, String scopeExpr) {
        parseSlots(s, query);
        // 标题缺失：从输入第一句猜测或追问
        if (s.title.isBlank()) {
            String guess = guessTitle(query);
            if (guess != null) {
                s.title = guess;
            } else {
                return new WizardResult("还差一个【标题】——文档叫什么名字？", "DOC_WIZARD", null, null);
            }
        }
        // 要点：标题确定后清洗（防空标题 replace 逐字拆散）
        String points = cleanPoints(query, s.title);
        if (!points.isEmpty() && !isConfirmWord(points) && !isFinalizeWord(points)) {
            s.points = s.points.isEmpty() ? points : s.points + "；" + points;
        }
        s.stage = Stage.CONFIRM;
        return new WizardResult(buildConfirmText(s), "DOC_WIZARD", null, null);
    }

    /** 确认：用户可纠正任意槽位；「确认/好的/生成」→ 生成全文进预览 */
    private WizardResult confirm(State s, String query, String scopeExpr) {
        String q = query.strip();
        if (isConfirmWord(q)) {
            return generate(s, scopeExpr);
        }
        // 视为纠正：解析槽位（标题可能被改）后重洗要点并回显
        parseSlots(s, q);
        String points = cleanPoints(q, s.title);
        if (!points.isEmpty() && !isConfirmWord(points) && !isFinalizeWord(points)) {
            s.points = s.points.isEmpty() ? points : s.points + "；" + points;
        }
        return new WizardResult(buildConfirmText(s), "DOC_WIZARD", null, null);
    }

    /** 预览/修改循环：定稿词 → 出文件；其余全部视为修改指令 */
    private WizardResult preview(State s, String key, String query, String scopeExpr) {
        String q = query.strip();
        if (isFinalizeWord(q)) {
            String fileName = safeFileTitle(s.title);
            byte[] file = docAssistant.toFile(s.format, s.title, s.content, buildFooter(s));
            // docx 直出飞书云文档（在线协作）；失败/其他格式回退文件消息
            if ("docx".equals(s.format) && cloudDocEnabled) {
                try {
                    String docId = feishuClient.createCloudDoc(s.title);
                    java.util.List<String> blockIds = docId != null
                            ? feishuClient.writeCloudDocBlocks(docId,
                                    toBlocks(s.content + "\n\n" + buildFooter(s)))
                            : null;
                    if (docId != null && blockIds != null && !blockIds.isEmpty()) {
                        // 排版：首块（文档标题行）居中，落款行右对齐
                        feishuClient.updateBlockAlign(docId, blockIds.get(0), 2);
                        for (int idx : signoffIndexes(s.content + "\n\n" + buildFooter(s))) {
                            if (idx > 0 && idx < blockIds.size()) {
                                feishuClient.updateBlockAlign(docId, blockIds.get(idx), 3);
                            }
                        }
                        feishuClient.makeCloudDocOrgReadable(docId);
                        String editor = editorHint.get();
                        if (editor != null && !editor.isBlank()) {
                            feishuClient.addDocEditor(docId, editor);
                        }
                        String url = feishuClient.cloudDocUrl(docId);
                        log.info("[文档向导] 云文档已创建 {}", url);
                        states.remove(key);   // 定稿即退出向导
                        return new WizardResult(
                                "文档已创建为飞书云文档（在线可编辑）：\n" + url
                                        + "\n\n文档就此定稿，有需要随时再找我。",
                                "DOC_WIZARD", null, null);
                    }
                } catch (Exception e) {
                    log.warn("[文档向导] 云文档创建失败，回退文件: {}", e.getMessage());
                }
            }
            String reply = "已按预览生成文件。文档就此定稿，有需要随时再找我。";
            states.remove(key);   // 定稿即退出向导（后续消息回普通问答）
            return new WizardResult(reply, "DOC_WIZARD", fileName + "." + s.format, file);
        }
        // 修改指令：可能包含格式/落款切换 + 内容修改 + 知识库引用
        parseSlots(s, q);
        String instruction = stripMetaWords(q);
        if (instruction.isBlank()) {
            return new WizardResult("没太明白这条修改要求，能再说具体点吗？（改哪段、怎么改）",
                    "DOC_WIZARD", null, null);
        }
        String kb = resolveKbReference(q, scopeExpr);
        String revisePrompt = buildRevisePrompt(s.content, instruction, kb);
        long start = System.currentTimeMillis();
        String out = docAssistant.getChatModel().chat(revisePrompt);
        log.info("[文档向导] 修改耗时 {}ms", System.currentTimeMillis() - start);
        if (out == null || out.isBlank()) {
            return new WizardResult("这次修改没成功，请再说一遍要求。", "DOC_WIZARD", null, null);
        }
        // 摘要与正文分离：定稿文档里不能混入【修改摘要】，且摘要行会把落款挤出末 4 行检测范围
        int sumIdx = out.indexOf("【修改摘要】");
        s.content = (sumIdx >= 0 ? out.substring(0, sumIdx) : out).strip();
        String summary = extractSummary(out);
        return new WizardResult(buildPreviewText(s, summary), "DOC_WIZARD", null, null);
    }

    /** 生成：注入知识库资料 → LLM 出全文 → 预览 */
    private WizardResult generate(State s, String scopeExpr) {
        String kb = resolveKbReference(s.points, scopeExpr);
        String prompt = buildGeneratePrompt(s, kb);
        long start = System.currentTimeMillis();
        String content = docAssistant.getChatModel().chat(prompt);
        log.info("[文档向导] 生成耗时 {}ms 长度 {} 字", System.currentTimeMillis() - start,
                content == null ? 0 : content.length());
        if (content == null || content.isBlank()) {
            s.stage = Stage.CONFIRM;
            return new WizardResult("生成失败，请再回复一次「确认」重试。", "DOC_WIZARD", null, null);
        }
        s.content = content;
        s.stage = Stage.PREVIEW;
        return new WizardResult(buildPreviewText(s, null), "DOC_WIZARD", null, null);
    }

    // ==================== 槽位解析 ====================

    private static final Map<String, String> TYPES = Map.of(
            "通知", "通知", "报告", "报告", "方案", "方案", "周报", "周报",
            "纪要", "会议纪要", "总结", "总结", "通用", "通用");

    private static final Map<String, String> TONES = Map.of(
            "正式", "正式", "公文", "正式", "简洁", "简洁直接", "直接", "简洁直接",
            "亲切", "亲切自然", "自然", "亲切自然");

    private static final Map<String, String> FORMATS = Map.of(
            "docx", "docx", "word", "docx", "pdf", "pdf", "md", "md", "markdown", "md", "txt", "txt");

    private void parseSlots(State s, String query) {
        String q = query;
        for (Map.Entry<String, String> e : TYPES.entrySet()) {
            if (q.contains(e.getKey())) {
                s.docType = e.getValue();
                break;
            }
        }
        for (Map.Entry<String, String> e : TONES.entrySet()) {
            if (q.contains(e.getKey())) {
                s.tone = e.getValue();
                break;
            }
        }
        for (Map.Entry<String, String> e : FORMATS.entrySet()) {
            if (q.toLowerCase().contains(e.getKey())) {
                s.format = e.getValue();
                break;
            }
        }
        Matcher issuer = Pattern.compile("落款[：:]?\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,12})").matcher(q);
        if (issuer.find()) {
            s.issuer = issuer.group(1);
        }
        Matcher title = Pattern.compile("标题[：:是]?\\s*(改成|改为|换成)?\\s*[「《]?([^，。；\\n]{2,40})[」》]?").matcher(q);
        if (title.find()) {
            s.title = title.group(2).strip();
        }
        // 要点不在此处拼接：统一由 cleanPoints（在标题确定后）处理，防空标题 replace("")
    }

    /** 要点清洗：去掉标题/槽位元词后的剩余文本 */
    private String cleanPoints(String query, String title) {
        String rest = query.replaceAll("落款[：:]?\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{2,12}", "")
                .replaceAll("标题[：:是]?\\s*(改成|改为|换成)?\\s*[「《]?[^，。；\\n]{2,40}[」》]?", "");
        if (title != null && !title.isBlank()) {
            rest = rest.replace(title, " ");
        }
        for (String kw : TYPES.keySet()) {
            rest = rest.replace(kw, " ");
        }
        for (String kw : TONES.keySet()) {
            rest = rest.replace(kw, " ");
        }
        for (String kw : FORMATS.keySet()) {
            rest = rest.replace(kw, " ");
        }
        return rest.replaceAll("默认|帮我|生成|写一个|写份|一份|麻烦|请|谢谢|的文档|语气|格式|类型", " ")
                .replaceAll("[，。；、\\n]+", " ").replaceAll("\\s{2,}", " ").strip();
    }

    /** 从输入猜测标题：优先"关于…"句式；书名号仅当不是"参考/依据《…》"的引用语境 */
    private String guessTitle(String query) {
        Matcher m = Pattern.compile("(关于[^，。；\\n]{2,40})").matcher(query);
        if (m.find()) {
            return m.group(1);
        }
        m = Pattern.compile("(?<![参考依据引用见])[《「]([^》」]{2,40})[》」]").matcher(query);
        if (m.find()) {
            return m.group(1);
        }
        // 短输入且无句读：整体即标题（"2026年9月工作总结"）
        String stripped = query.strip();
        if (stripped.length() <= 20 && !stripped.matches(".*[，。；、\\n].*")) {
            return stripped;
        }
        return null;
    }

    // ==================== 知识库注入 ====================

    private static final Pattern DOC_REF = Pattern.compile("[《「]([^》」]{2,40})[》」]|参考\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{2,30})");

    /** 提取引用文档名并检索（按员工范围），拼接成【参考资料】块；无引用返回空串 */
    private String resolveKbReference(String text, String scopeExpr) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder kb = new StringBuilder();
        Matcher m = DOC_REF.matcher(text);
        while (m.find()) {
            String docName = m.group(1) != null ? m.group(1) : m.group(2);
            try {
                RetrievalPipeline.PipelineResult pre =
                        retrievalPipeline.execute(docName, scopeExpr, false);
                if (!pre.segments().isEmpty()) {
                    kb.append("【参考资料：").append(docName).append("】\n")
                            .append(pre.segments().stream().limit(3)
                                    .map(s -> s.text()).collect(java.util.stream.Collectors.joining("\n\n")))
                            .append("\n[/参考资料]\n\n");
                }
            } catch (Exception e) {
                log.warn("[文档向导] 知识库引用检索失败 {}: {}", docName, e.getMessage());
            }
        }
        return kb.toString();
    }

    // ==================== 提示词 ====================

    private String buildGeneratePrompt(State s, String kb) {
        return """
                你是企业文档撰写助手。请按【%s】的标准结构撰写《%s》。
                语气要求：%s。
                内容要点：%s
                %s
                写作要求：
                1. 只输出正文本身，不要任何解释、前缀或元话语
                2. 不使用 emoji 和装饰符号，纯文字排版
                3. 用 markdown 组织：## 分节标题 + 段落；编号用 1. 2. 3.
                4. 落款单独一段：%s 和今天日期（%s）
                5. 引用资料里的条文必须逐字准确，不得改写数字与条款号
                6. 今年是 %s 年：正文中涉及年份与星期必须与今年实际日历一致，
                   不确定的星期几不要写
                """.formatted(s.docType, s.title, s.tone,
                s.points.isBlank() ? "（无额外要点，按常规结构撰写）" : s.points,
                kb.isBlank() ? "" : kb,
                s.issuer, java.time.LocalDate.now(),
                java.time.LocalDate.now().getYear());
    }

    private String buildRevisePrompt(String current, String instruction, String kb) {
        return """
                你是文档编辑。根据修改要求修改下面的文档，直接输出修改后的完整正文。
                规则：
                1. 只修改要求涉及的部分，其余段落保持原文不变
                2. 不使用 emoji 和装饰符号
                3. 引用资料里的条文必须逐字准确
                4. 输出修改后全文后，空一行输出【修改摘要】，用 3 条以内要点概括改动

                %s

                [当前文档]
                %s
                [/当前文档]

                [修改要求]
                %s
                """.formatted(kb.isBlank() ? "" : kb, current, instruction);
    }

    // ==================== 展示 ====================

    private String buildConfirmText(State s) {
        return "生成信息确认\n"
                + "【类型】" + s.docType + "\n"
                + "【标题】" + s.title + "\n"
                + "【要点】" + (s.points.isBlank() ? "（无）" : s.points) + "\n"
                + "【语气】" + s.tone + "\n"
                + "【格式】" + s.format + "\n"
                + "【落款】" + s.issuer + " · " + java.time.LocalDate.now() + "\n\n"
                + "回复「确认」开始生成；或直接说需要调整的项（如「格式改pdf」「落款改行政部」）。";
    }

    private String buildPreviewText(State s, String summary) {
        String head = summary == null || summary.isBlank()
                ? "已生成初稿，如下。回复修改要求（如「第二段精简」「标题改xxx」「格式改pdf」），"
                + "回复「没问题」或「生成文件」即按当前内容出文件。\n\n"
                : "已按你的要求修改：\n" + summary + "\n\n当前版本：\n\n";
        String content = s.content.length() > 2000
                ? s.content.substring(0, 2000) + "\n\n（预览已截断，全文以定稿文件为准）"
                : s.content;
        return head + content;
    }

    /** markdown → 飞书云文档 blocks（2 文本 / 3 一级标题 / 4 二级标题 / 12 无序 / 13 有序）。
     *  排版：正文段落前置两个全角空格（首行缩进，API 无缩进属性）；落款行尾缀两个全角空格
     *  （右对齐留边距，贴边观感过远） */
    private java.util.List<java.util.Map<String, Object>> toBlocks(String markdown) {
        java.util.Set<String> signoff = signoffLines(markdown);
        java.util.List<java.util.Map<String, Object>> blocks = new java.util.ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String s = line.strip();
            if (s.isEmpty() || s.startsWith("---")) {
                continue;
            }
            int type;
            String text;
            if (s.startsWith("###")) {
                type = 4;
                text = s.replaceFirst("^#+\\s*", "");
            } else if (s.startsWith("#")) {
                type = 3;
                text = s.replaceFirst("^#+\\s*", "");
            } else if (s.matches("^\\d+[\\.、].*")) {
                type = 13;   // ordered 列表块 = 13（实测：12/13 反了会 400 invalid param）
                text = "　" + s.replaceFirst("^\\d+[\\.、]\\s*", "");   // 编号后缩进对齐正文
            } else if (s.matches("^[-*•].*")) {
                type = 12;   // bullet 列表块 = 12
                text = "　" + s.replaceFirst("^[-*•]\\s*", "");
            } else {
                type = 2;
                text = signoff.contains(s) ? s + "\u3000\u3000" : "\u3000\u3000" + s;
            }
            String key = switch (type) {
                case 3 -> "heading1";
                case 4 -> "heading2";
                case 13 -> "ordered";
                case 12 -> "bullet";
                default -> "text";
            };
            blocks.add(java.util.Map.of("block_type", type, key, java.util.Map.of("elements",
                    java.util.List.of(java.util.Map.of("text_run",
                            java.util.Map.of("content", text))))));
        }
        return blocks;
    }

    /** 工作台云文档导出（复用向导同套排版规则；云文档自带标题，正文首行重复标题的 heading 移除） */
    public java.util.List<java.util.Map<String, Object>> toBlocksForExport(String markdown, String title) {
        if (title != null && !title.isBlank()) {
            String[] lines = markdown.split("\n", 2);
            String first = lines[0] == null ? "" : lines[0].strip();
            if (first.startsWith("#")) {
                String h = first.replaceFirst("^#+\\s*", "");
                if (sameTitle(h, title.strip())) {
                    markdown = lines.length > 1 ? lines[1] : "";
                }
            }
        }
        return toBlocks(markdown);
    }

    /** 同题异写判定：去数字空白后 LCS 相似度 ≥0.85（模型常加年份/机构修饰词，如"2026年""劳动节"） */
    private static boolean sameTitle(String a, String b) {
        String x = a.replaceAll("[\\s\\d]", "");
        String y = b.replaceAll("[\\s\\d]", "");
        if (x.length() < 5 || y.length() < 5) {
            return x.equals(y);
        }
        int m = x.length(), n = y.length();
        int[] prev = new int[n + 1];
        for (int i = 1; i <= m; i++) {
            int[] cur = new int[n + 1];
            for (int j = 1; j <= n; j++) {
                cur[j] = x.charAt(i - 1) == y.charAt(j - 1)
                        ? prev[j - 1] + 1
                        : Math.max(prev[j], cur[j - 1]);
            }
            prev = cur;
        }
        return prev[n] * 1.0 / Math.min(m, n) >= 0.85;
    }

    /** 落款行集合（末 4 行内短中文行/日期行） */
    private java.util.Set<String> signoffLines(String markdown) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("---")) {
                lines.add(s);
            }
        }
        java.util.Set<String> signoff = new java.util.HashSet<>();
        for (int i = Math.max(0, lines.size() - 4); i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.matches("[\\u4e00-\\u9fa5]{2,12}")
                    || s.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")
                    || s.matches("\\d{4}年\\d{1,2}月\\d{1,2}日")) {
                signoff.add(s);
            }
        }
        return signoff;
    }

    /** 落款行的块下标（末 4 行内短中文行/日期行；与 toBlocks 同序跳过规则） */
    private java.util.List<Integer> signoffIndexes(String markdown) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        for (String line : markdown.split("\n", -1)) {
            String s = line.strip();
            if (!s.isEmpty() && !s.startsWith("---")) {
                lines.add(s);
            }
        }
        java.util.Set<String> signoff = new java.util.HashSet<>();
        for (int i = Math.max(0, lines.size() - 4); i < lines.size(); i++) {
            String s = lines.get(i);
            if (s.matches("[\\u4e00-\\u9fa5]{2,12}")
                    || s.matches("\\d{4}[-/]\\d{1,2}[-/]\\d{1,2}")
                    || s.matches("\\d{4}年\\d{1,2}月\\d{1,2}日")) {
                signoff.add(s);
            }
        }
        java.util.List<Integer> indexes = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            if (signoff.contains(lines.get(i))) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private String buildFooter(State s) {
        return FOOTER;
    }

    // ==================== 工具方法 ====================

    private static boolean isConfirmWord(String q) {
        return q.matches("确认|好的|可以|生成吧|直接生成|开始生成|ok|OK|行|没问题，生成");
    }

    private static boolean isFinalizeWord(String q) {
        return q.matches("没问题|就这样|好了|完成|保存|定稿|生成文件|就按这个|可以了|结束|ok|OK|行");
    }

    /** 修改指令里剥掉槽位/确认类元词 */
    private String stripMetaWords(String q) {
        String rest = q.replaceAll("落款[：:]?\\s*[\\u4e00-\\u9fa5A-Za-z0-9]{2,12}", "")
                .replaceAll("标题[：:是]?\\s*[「《]?[^，。；\\n]{2,40}[」》]?", "");
        for (String kw : TONES.keySet()) {
            rest = rest.replace(kw, " ");
        }
        for (String kw : FORMATS.keySet()) {
            rest = rest.replace(kw, " ");
        }
        return rest.replaceAll("\\s{2,}", " ").strip();
    }

    private static String extractSummary(String out) {
        int idx = out.indexOf("【修改摘要】");
        return idx >= 0 ? out.substring(idx).strip() : "已按要求修改。";
    }

    private String resolveIssuer(Long employeeId) {
        String dept = deptHint.get();
        if (dept != null && !dept.isBlank()) {
            return dept;
        }
        return defaultIssuer != null && !defaultIssuer.isBlank() ? defaultIssuer : "公司";
    }

    private static String safeFileTitle(String title) {
        return title == null || title.isBlank() ? "文档"
                : title.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+$", "");
    }

    // ==================== 存档与待发文件（历史按用户隔离；编排器在定稿后调用） ====================

    /** 待发文件暂存（飞书桥在回复后取出发送；短时窗口防泄漏） */
    public record Pending(String fileName, byte[] bytes) {}

    private final ConcurrentHashMap<String, Pending> pendingFiles = new ConcurrentHashMap<>();

    public void putPending(String wizardKey, String fileName, byte[] bytes) {
        pendingFiles.put(wizardKey, new Pending(fileName, bytes));
    }

    /** 取出并清除待发文件（一次性消费） */
    public Pending takePending(String wizardKey) {
        return pendingFiles.remove(wizardKey);
    }

    /** 本轮回是否将触发慢生成（确认阶段回复"确认"）——飞书桥用于先回"正在处理" */
    public boolean willGenerateSlow(String key, String query) {
        State s = states.get(key);
        return s != null && s.stage == Stage.CONFIRM
                && isConfirmWord(query == null ? "" : query.strip());
    }

    /** 定稿文件存档到 data/docgen/{userKey}/ */
    public void archive(String userKey, String fileName, byte[] bytes) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("data", "docgen",
                    userKey.replaceAll("[^\\w\\-]", "_"));
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.write(dir.resolve(fileName), bytes);
            log.info("[文档向导] 已存档 {}", dir.resolve(fileName));
        } catch (Exception e) {
            log.warn("[文档向导] 存档失败: {}", e.getMessage());
        }
    }

    /** 历史文件列表（按用户目录隔离；调用方传用户标识） */
    public List<Map<String, Object>> history(String userKey) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("data", "docgen",
                    userKey.replaceAll("[^\\w\\-]", "_"));
            if (!java.nio.file.Files.isDirectory(dir)) {
                return List.of();
            }
            return java.nio.file.Files.list(dir)
                    .filter(p -> p.toString().endsWith(".docx") || p.toString().endsWith(".pdf")
                            || p.toString().endsWith(".md") || p.toString().endsWith(".txt"))
                    .sorted(java.util.Comparator.comparingLong(p -> -p.toFile().lastModified()))
                    .map(p -> Map.<String, Object>of(
                            "name", p.getFileName().toString(),
                            "size", p.toFile().length(),
                            "time", new java.util.Date(p.toFile().lastModified()).toString()))
                    .limit(50)
                    .toList();
        } catch (Exception e) {
            return List.of();
        }
    }

    /** 下载历史文件 */
    public byte[] historyFile(String userKey, String name) {
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("data", "docgen",
                    userKey.replaceAll("[^\\w\\-]", "_"));
            if (!name.matches("[\\w\\-]+\\.(docx|pdf|md|txt)")) {
                return null;   // 文件名白名单，防路径穿越
            }
            java.nio.file.Path p = dir.resolve(name);
            return java.nio.file.Files.exists(p) ? java.nio.file.Files.readAllBytes(p) : null;
        } catch (Exception e) {
            return null;
        }
    }
}
