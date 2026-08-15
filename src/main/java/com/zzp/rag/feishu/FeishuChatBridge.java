package com.zzp.rag.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.agent.AgentOrchestrator;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.audit.AuditService;
import com.zzp.rag.web.StreamTokenFixer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 飞书对话桥（渠道适配器核心）：飞书消息 → 数字员工对话 → 回复。
 * <p>
 * 两个入口共用一个处理核心：
 * - SDK 长连接（WS）事件 → {@link #handleMessage(String, String, String, String)}
 * - HTTP 回调事件（JsonNode）→ {@link #handleEvent(JsonNode)} 拆包后调同一核心
 * <p>
 * 设计要点：
 * - conversationId = chat_id（每个群/单聊一份会话记忆，与 Web 端隔离）
 * - agentId 默认取配置（数字员工绑定：人设/知识范围/技能授权全链路生效）
 * - 审计记录 endpoint=feishu/chat（所有渠道一个审计真相源，合规要求）
 * - 软依赖：任何一步失败只记日志，尽力回复兜底文案，绝不让回调挂掉
 */
@Slf4j
@Component
public class FeishuChatBridge {

    private final FeishuConfig config;
    private final FeishuClient client;
    private final AgentOrchestrator orchestrator;
    private final com.zzp.rag.agent.DocWizardService docWizardService;
    private final com.zzp.rag.feishu.FeishuFileContextService fileContextService;
    private final com.zzp.rag.ingestion.DocumentParser documentParser;
    private final com.zzp.rag.ingestion.IngestionPipeline ingestionPipeline;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 对话生成要几十秒，事件处理必须异步（SDK ack 后飞书才不重投）；按 messageId 去重兜底重投 */
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "feishu-chat");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.ConcurrentHashMap<String, Long> seenMessageIds =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 表单提交进行中（按卡片 messageId 去重：飞书回调 3s 超时会重投） */
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> formInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();

    public FeishuChatBridge(FeishuConfig config, FeishuClient client,
                            AgentOrchestrator orchestrator,
                            com.zzp.rag.agent.DocWizardService docWizardService,
                            FeishuFileContextService fileContextService,
                            com.zzp.rag.ingestion.DocumentParser documentParser,
                            com.zzp.rag.ingestion.IngestionPipeline ingestionPipeline,
                            AuditService auditService,
                            ObjectMapper objectMapper) {
        this.config = config;
        this.client = client;
        this.orchestrator = orchestrator;
        this.docWizardService = docWizardService;
        this.fileContextService = fileContextService;
        this.documentParser = documentParser;
        this.ingestionPipeline = ingestionPipeline;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** HTTP 回调模式入口：schema 2.0 事件体 → 拆包 → 处理核心（sender open_id 作审计身份） */
    public void handleEvent(JsonNode event) {
        JsonNode message = event.path("event").path("message");
        String senderOpenId = event.path("event").path("sender")
                .path("sender_id").path("open_id").asText("");
        handleMessage(
                message.path("chat_id").asText(),
                message.path("message_id").asText(),
                message.path("message_type").asText(),
                message.path("content").asText(),
                senderOpenId);
    }

    /** SDK 长连接/HTTP 共用的处理核心：立即返回（事件 ack），处理走工作线程 + messageId 去重 */
    public void handleMessage(String chatId, String messageId, String messageType, String contentJson,
                              String senderOpenId) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        Long prev = seenMessageIds.putIfAbsent(messageId, System.currentTimeMillis());
        if (prev != null) {
            log.info("[飞书] 重复消息跳过（重投） messageId={}", messageId);
            return;
        }
        if (seenMessageIds.size() > 2000) {
            seenMessageIds.clear();   // 兜底防膨胀（正常不会到：消息 id 短时窗口去重）
        }
        worker.execute(() -> doHandle(chatId, messageId, messageType, contentJson, senderOpenId));
    }

    private void doHandle(String chatId, String messageId, String messageType, String contentJson,
                          String senderOpenId) {
        // 审计身份：飞书 open_id（无则回退 MDC/请求头/anonymous）
        String auditUser = senderOpenId != null && !senderOpenId.isBlank() ? senderOpenId : null;
        AuditEvent.AuditContext ctx = AuditEvent.AuditContext.capture();
        String query = null;
        long start = System.currentTimeMillis();
        try {
            if (chatId == null || chatId.isBlank()) {
                log.info("[飞书] 跳过：缺少 chatId type={}", messageType);
                return;
            }
            // 转发文件：下载 → 解析 → 建立会话文件上下文（后续提问自动结合文件作答）
            if ("file".equals(messageType)) {
                handleFileMessage(chatId, messageId, contentJson, auditUser, ctx, start);
                return;
            }
            if ("image".equals(messageType)) {
                client.replyMessage(messageId,
                        "图片暂不支持解析，请发送文件（docx / pdf / xlsx / pptx / txt 等）。");
                return;
            }
            if (!"text".equals(messageType)) {
                log.info("[飞书] 跳过非文本消息 chat={} type={}", chatId, messageType);
                return;
            }
            query = parseText(contentJson);
            if (query.isBlank()) {
                log.info("[飞书] 文本为空，跳过 chat={}", chatId);
                return;
            }
            log.info("[飞书] 收到消息 chat={} query={}", chatId, query);

            // 文档向导：key = 会话+用户（与卡片回调、takePending 的 key 必须一致）
            String wizardUser = auditUser;
            String wizardKey = chatId + ":" + (wizardUser == null ? "anon" : wizardUser);

            // 转发文件上下文指令（入库/清除）——优先于向导与其他路由
            FeishuFileContextService.FileCtx fileCtx = fileContextService.get(chatId, wizardUser);
            if (fileCtx != null && query.strip().matches("入库|收入知识库|加入知识库|存到知识库|存入知识库")) {
                ingestFileToKb(fileCtx, chatId, messageId, wizardUser, ctx, start);
                return;
            }
            if (fileCtx != null && query.strip().matches("清除文件|移除文件|不用这个文件了|删掉文件")) {
                fileContextService.clear(chatId, wizardUser);
                client.replyMessage(messageId, "已移除文件《" + fileCtx.fileName()
                        + "》，后续提问回到知识库模式。");
                return;
            }

            // 新触发：一句话直达生成（带「关于…」标题）→ 云文档直出；否则弹表单卡片
            if (docWizardService.isFreshTrigger(wizardKey, query)
                    && docWizardService.canDirectGenerate(query)) {
                com.zzp.rag.agent.DocWizardService.DirectResult r =
                        docWizardService.directGenerate(wizardKey, query, auditUser);
                if (r.cloudUrl() != null) {
                    String card = buildDocResultCard(r.title(), r.cloudUrl());
                    if (card == null || !client.replyCard(messageId, card)) {
                        client.replyMessage(messageId,
                                "已生成《" + r.title() + "》云文档：\n" + r.cloudUrl());
                    }
                } else {
                    // 云文档不可用 → docx 文件兜底
                    byte[] file = docWizardService.toDocxFile(r.title(), r.content());
                    String fileKey = client.uploadFile(
                            r.title().strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".docx", file);
                    if (fileKey != null) {
                        client.sendFileMessage(chatId, fileKey);
                    }
                    String card = buildDocResultCard(r.title(), null);
                    if (card == null || !client.replyCard(messageId, card)) {
                        client.replyMessage(messageId, "已生成《" + r.title() + "》docx 文件并发送到会话。");
                    }
                }
                auditService.record(new AuditEvent(
                        ctx.traceId(),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                        "DOC_WIZARD", query, 0, List.of(), 0,
                        false, 0, "一句话直达生成：" + r.title(), r.content().length(),
                        System.currentTimeMillis() - start, "SUCCESS", null, 0));
                return;
            }
            if (docWizardService.isFreshTrigger(wizardKey, query)) {
                String formCard = buildDocFormCard();
                if (formCard != null && client.replyCard(messageId, formCard)) {
                    auditService.record(new AuditEvent(
                            ctx.traceId(),
                            LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                            "DOC_WIZARD", query, 0, List.of(), 0,
                            false, 0, "文档表单卡片", 0,
                            System.currentTimeMillis() - start, "SUCCESS", null, 0));
                    return;
                }
                // 卡片发送失败 → 落到文字向导兜底
            }

            // 文档向导慢生成（确认生成/修改）先回"正在处理"，避免用户干等
            if (docWizardService.willGenerateSlow(wizardKey, query)) {
                client.replyMessage(messageId, "收到，正在生成文档，约需半分钟…");
            }
            // 数字员工对话：conversationId=chat_id（按群隔离记忆）；agentId 默认绑定。
            // 先把 open_id 写入 MDC：编排器里的向导 key 用 capture().user()，
            // 与下方 takePending 的 key 必须一致（历史坑：工作线程 MDC 为空导致文件漏发）
            String prevMdcUser = org.slf4j.MDC.get(AuditEvent.MDC_USER_KEY);
            if (auditUser != null) {
                org.slf4j.MDC.put(AuditEvent.MDC_USER_KEY, auditUser);
            }
            // 云文档编辑者授权：定稿时把发起用户加成文档编辑者（tenant_readable 仅可读）
            docWizardService.setEditorHint(auditUser);
            Long agentId = config.defaultAgentId() > 0 ? config.defaultAgentId() : null;
            AgentOrchestrator.AgentAnswer answer;
            try {
                answer = orchestrator.answer(chatId, query, agentId);
            } finally {
                docWizardService.clearEditorHint();
                if (prevMdcUser != null) {
                    org.slf4j.MDC.put(AuditEvent.MDC_USER_KEY, prevMdcUser);
                } else {
                    org.slf4j.MDC.remove(AuditEvent.MDC_USER_KEY);
                }
            }
            String markdown = StreamTokenFixer.fixFull(answer.answer());
            // 定稿文件推送（上传素材 + 发文件消息）
            com.zzp.rag.agent.DocWizardService.Pending pending =
                    docWizardService.takePending(wizardKey);
            if (pending != null) {
                String fileKey = client.uploadFile(pending.fileName(), pending.bytes());
                if (fileKey != null) {
                    client.sendFileMessage(chatId, fileKey);
                }
            }
            boolean replied;
            if (!"DOC_WIZARD".equals(answer.route()) && config.cardMode()) {
                String card = buildCard(query, markdown, answer.sources(), chatId);
                replied = card != null && client.replyCard(messageId, card);
                if (!replied) {
                    replied = client.replyMessage(messageId, toFeishuText(markdown));   // 卡片失败回退纯文本
                }
            } else {
                // 文档向导回复纯文本：表单卡片已替代文字交互，不再挂反馈卡片
                replied = client.replyMessage(messageId, toFeishuText(markdown));
            }
            if (!replied) {
                log.warn("[飞书] 回复失败 chat={} messageId={}", chatId, messageId);
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                    answer.route(), query, 0, answer.sources(), 0,
                    answer.rerankDegraded(), answer.graphContextChars(),
                    AuditEvent.truncate(answer.answer(), 500), answer.answer().length(),
                    System.currentTimeMillis() - start, "SUCCESS", null,
                    answer.employeeId()));
        } catch (Exception e) {
            log.error("[飞书] 消息处理失败 chat={}", chatId, e);
            if (messageId != null) {
                client.replyMessage(messageId, "抱歉，处理您的消息时出错了，请稍后再试。");
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                    null, query, 0, List.of(), 0,
                    false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
        }
    }

    /**
     * 转发文件处理：下载素材 → 解析（sidecar 双通道/Tika）→ 建立会话文件上下文。
     * 之后该会话的提问自动注入文件内容作答（见 AgentOrchestrator 的 promptBlock 注入）。
     */
    private void handleFileMessage(String chatId, String messageId, String contentJson,
                                   String auditUser, AuditEvent.AuditContext ctx, long start) {
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            String fileKey = node.path("file_key").asText(null);
            String fileNameHint = node.path("file_name").asText(null);
            if (fileKey == null || fileKey.isBlank()) {
                client.replyMessage(messageId, "未识别到文件，请重新转发。");
                return;
            }
            FeishuClient.DownloadedFile df = client.downloadResource(messageId, fileKey);
            if (df == null || df.bytes() == null || df.bytes().length == 0) {
                client.replyMessage(messageId, "文件下载失败，请重试（较大文件可稍等片刻再发）。");
                auditService.record(new AuditEvent(
                        ctx.traceId(),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/file",
                        null, fileNameHint, 0, List.of(), 0, false, 0, null, 0,
                        System.currentTimeMillis() - start, "FAILED", "下载失败", 0));
                return;
            }
            String fileName = (fileNameHint != null && !fileNameHint.isBlank())
                    ? fileNameHint : df.fileName();
            long parseStart = System.currentTimeMillis();
            com.zzp.rag.ingestion.ParsedDocument parsed = documentParser.parse(
                    new com.zzp.rag.ingestion.ByteMultipartFile(fileName, df.bytes()));
            String text = parsed.text();
            log.info("[飞书] 文件解析完成 file={} chars={} 耗时 {}ms",
                    fileName, text == null ? 0 : text.length(),
                    System.currentTimeMillis() - parseStart);
            if (text == null || text.isBlank()) {
                client.replyMessage(messageId,
                        "文件内容为空或无法解析（纯图片请发送 PDF 版或文字版）。");
                return;
            }
            boolean truncated = text.length() > FeishuFileContextService.MAX_CONTENT_CHARS;
            String stored = truncated ? text.substring(0, FeishuFileContextService.MAX_CONTENT_CHARS) : text;
            fileContextService.put(chatId, auditUser, fileName, stored, messageId, fileKey);
            client.replyMessage(messageId,
                    "已解析《" + fileName + "》（" + text.length() + " 字"
                            + (truncated ? "，超出 6 万字部分未保留" : "") + "）。\n"
                            + "接下来的提问我会结合这份文件回答；\n"
                            + "回复「入库」把文件收入知识库；回复「清除文件」移除。");
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/file",
                    null, fileName, 0, List.of(), 0, false, 0,
                    "chars=" + text.length(), text.length(),
                    System.currentTimeMillis() - start, "SUCCESS", null, 0));
        } catch (Exception e) {
            log.error("[飞书] 文件消息处理失败", e);
            client.replyMessage(messageId, "文件解析失败：" + e.getMessage());
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/file",
                    null, null, 0, List.of(), 0, false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
        }
    }

    /** 「入库」：重新下载原始字节 → 摄入管线（向量化+Milvus+图谱异步）→ 清理会话上下文 */
    private void ingestFileToKb(FeishuFileContextService.FileCtx fc, String chatId,
                                String messageId, String user,
                                AuditEvent.AuditContext ctx, long start) {
        try {
            client.replyMessage(messageId,
                    "收到，正在把《" + fc.fileName() + "》写入知识库，约需半分钟…");
            FeishuClient.DownloadedFile df = client.downloadResource(fc.messageId(), fc.fileKey());
            if (df == null) {
                client.replyMessage(messageId, "入库失败：文件已过期，请重新转发后再试。");
                return;
            }
            String docId = ingestionPipeline.ingest(
                    new com.zzp.rag.ingestion.ByteMultipartFile(fc.fileName(), df.bytes()),
                    fc.fileName(), "飞书转发");
            fileContextService.clear(chatId, user);
            client.replyMessage(messageId,
                    "已入库《" + fc.fileName() + "》，现在知识库检索可以找到它了。");
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    user != null ? user : ctx.user(), ctx.ip(), "feishu/file_ingest",
                    null, fc.fileName(), 0, List.of(), 0, false, 0,
                    docId, fc.content().length(),
                    System.currentTimeMillis() - start, "SUCCESS", null, 0));
        } catch (Exception e) {
            log.error("[飞书] 文件入库失败 file={}", fc.fileName(), e);
            client.replyMessage(messageId, "入库失败：" + e.getMessage());
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    user != null ? user : ctx.user(), ctx.ip(), "feishu/file_ingest",
                    null, fc.fileName(), 0, List.of(), 0, false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
        }
    }

    /**
     * 构建回答卡片（交互卡片：markdown 渲染正文 + 来源 note + 反馈按钮）。
     * 按钮 value 带动作上下文（重新生成需要原问题与群 ID）。
     * 用 ObjectMapper 序列化——手工拼接遇换行/引号会产出非法 JSON（飞书 400）。
     */
    private String buildCard(String query, String markdown, List<String> sources, String chatId) {
        String md = markdown.length() > 6000
                ? markdown.substring(0, 6000) + "\n\n…（内容过长已截断，完整内容请在 Web 端查看）"
                : markdown;
        md = toCardMarkdown(md);   // 飞书卡片 markdown 不支持表格，先展平
        String sourceNote = sources.isEmpty() ? "无来源" : String.join("、", sources);

        java.util.Map<String, Object> regenValue = new java.util.LinkedHashMap<>();
        regenValue.put("action", "regenerate");
        regenValue.put("query", query);
        regenValue.put("chatId", chatId);

        java.util.List<Object> elements = new java.util.ArrayList<>();
        elements.add(java.util.Map.of("tag", "markdown", "content", md));
        elements.add(java.util.Map.of("tag", "hr"));
        elements.add(java.util.Map.of("tag", "note", "elements",
                java.util.List.of(java.util.Map.of("tag", "plain_text",
                        "content", "📄 来源：" + sourceNote))));
        elements.add(java.util.Map.of("tag", "action", "actions", java.util.List.of(
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "👍 有帮助"),
                        "type", "primary",
                        "value", java.util.Map.of("action", "helpful")),
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "👎 无帮助"),
                        "type", "default",
                        "value", java.util.Map.of("action", "unhelpful")),
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "🔄 重新生成"),
                        "type", "default",
                        "value", regenValue))));

        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("config", java.util.Map.of("wide_screen_mode", true));
        card.put("header", java.util.Map.of(
                "title", java.util.Map.of("tag", "plain_text", "content", "🤖 数字员工回答"),
                "template", "blue"));
        card.put("elements", elements);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("[飞书] 卡片构建失败（回退纯文本）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 文档表单卡片（卡片 JSON 2.0）：input/select 必须嵌在 form 容器内才渲染，
     * 提交按钮放在容器内用 form_action_type=submit，点击回传 action.form_value。
     * 纯文本无图标；替代原文字多轮追问。
     */
    private String buildDocFormCard() {
        java.util.List<Object> formChildren = new java.util.ArrayList<>();
        formChildren.add(java.util.Map.of("tag", "select_static",
                "name", "docType",
                "placeholder", java.util.Map.of("tag", "plain_text", "content", "文档类型"),
                "initial_option", "通知",
                "options", java.util.List.of("通知", "报告", "方案", "周报", "会议纪要", "制度办法", "通用文档")
                        .stream().map(t -> java.util.Map.of(
                                "text", java.util.Map.of("tag", "plain_text", "content", t),
                                "value", t)).toList()));
        formChildren.add(java.util.Map.of("tag", "input",
                "name", "title",
                "required", true,
                "label", java.util.Map.of("tag", "plain_text", "content", "标题"),
                "placeholder", java.util.Map.of("tag", "plain_text",
                        "content", "如：关于五一假期安排的通知")));
        formChildren.add(java.util.Map.of("tag", "input",
                "name", "points",
                "input_type", "multiline_text",   // 多行输入框：tag 必须是 input（textarea 会被飞书拒 11310）
                "rows", 4,
                "auto_resize", true,
                "label", java.util.Map.of("tag", "plain_text", "content", "内容要点"),
                "placeholder", java.util.Map.of("tag", "plain_text",
                        "content", "每行一条要点；可引用知识库文档，如「参考《员工年休假管理办法》」")));
        formChildren.add(java.util.Map.of("tag", "select_static",
                "name", "tone",
                "placeholder", java.util.Map.of("tag", "plain_text", "content", "语气"),
                "initial_option", "正式公文语气",
                "options", java.util.List.of("正式公文语气", "简洁直接", "亲切自然")
                        .stream().map(t -> java.util.Map.of(
                                "text", java.util.Map.of("tag", "plain_text", "content", t),
                                "value", t)).toList()));
        formChildren.add(java.util.Map.of("tag", "input",
                "name", "issuer",
                "label", java.util.Map.of("tag", "plain_text", "content", "落款"),
                "placeholder", java.util.Map.of("tag", "plain_text",
                        "content", "如：行政部（不填默认公司）")));
        formChildren.add(java.util.Map.of("tag", "button",
                "text", java.util.Map.of("tag", "plain_text", "content", "生成文档"),
                "type", "primary",
                "form_action_type", "submit",
                "name", "submit_btn",
                "value", java.util.Map.of("action", "doc_form_submit")));

        java.util.List<Object> elements = new java.util.ArrayList<>();
        elements.add(java.util.Map.of("tag", "markdown",
                "content", "请填写以下信息，点【生成文档】即可直接产出飞书云文档："));
        elements.add(java.util.Map.of("tag", "form", "name", "doc_form", "elements", formChildren));

        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("schema", "2.0");
        card.put("config", java.util.Map.of("update_multi", true));
        card.put("header", java.util.Map.of(
                "title", java.util.Map.of("tag", "plain_text", "content", "帮我写文档"),
                "template", "blue"));
        card.put("body", java.util.Map.of("elements", elements));
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("[飞书] 文档表单卡片构建失败: {}", e.getMessage());
            return null;
        }
    }

    /** 表单提交结果卡片：只留「打开云文档」（url 为 null 表示回退文件已发）；
     *  不跳后台工作台——云文档本身就是编辑器，修改走群聊指令回写同一份 */
    private String buildDocResultCard(String title, String url) {
        java.util.List<Object> elements = new java.util.ArrayList<>();
        elements.add(java.util.Map.of("tag", "markdown",
                "content", url != null
                        ? "已生成《" + title + "》，可直接在线编辑。"
                        : "已生成《" + title + "》docx 文件并发送到会话，请查收。"));

        if (url != null) {
            elements.add(java.util.Map.of("tag", "action", "actions", java.util.List.of(
                    java.util.Map.of("tag", "button",
                            "text", java.util.Map.of("tag", "plain_text", "content", "打开云文档"),
                            "type", "primary",
                            "url", url,
                            "value", java.util.Map.of("action", "open_doc")))));
        }
        elements.add(java.util.Map.of("tag", "note", "elements",
                java.util.List.of(java.util.Map.of("tag", "plain_text",
                        "content", "需要调整？直接在群里回复修改要求（如「第二段精简」「落款改行政部」），"
                                + "我会改好并同步更新到这份云文档；回复「定稿」出最终版。"))));

        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("config", java.util.Map.of("wide_screen_mode", true));
        card.put("header", java.util.Map.of(
                "title", java.util.Map.of("tag", "plain_text", "content", "帮我写文档"),
                "template", "blue"));
        card.put("elements", elements);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("[飞书] 文档结果卡片构建失败: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 卡片按钮回调（card.action.trigger）：
     * - helpful/unhelpful → 审计记录 + 感谢回复
     * - regenerate → 重新执行对话 → 原卡片原地更新
     * - doc_form_submit → 读表单字段 → 生成全文 → 云文档直出（失败回退 docx 文件）→ 卡片原地换结果
     */
    public void handleCardAction(java.util.Map<String, Object> value,
                                 java.util.Map<String, Object> formValue,
                                 String openMessageId, String openChatId, String operator) {
        String action = value != null ? String.valueOf(value.getOrDefault("action", "")) : "";
        if (action.isBlank() && formValue != null && !formValue.isEmpty()) {
            action = "doc_form_submit";   // 表单提交按钮可能不带 value，按 form_value 判定
        }
        log.info("[飞书] 卡片动作 {} messageId={} chat={}", action, openMessageId, openChatId);
        switch (action) {
            case "helpful", "unhelpful" -> {
                auditService.record(new AuditEvent(
                        AuditEvent.AuditContext.capture().traceId(),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        operator != null ? operator : "anonymous",
                        "-", "feishu/card_feedback",
                        null, action, 0, List.of(), 0,
                        false, 0, null, 0, 0, "SUCCESS", null, 0));
                client.replyMessage(openMessageId, "helpful".equals(action) ? "感谢反馈 👍" : "收到反馈，我们会持续改进 👎");
            }
            case "regenerate" -> {
                String query = value != null ? String.valueOf(value.getOrDefault("query", "")) : "";
                String chatId = value != null ? String.valueOf(value.getOrDefault("chatId", "")) : "";
                if (query.isBlank() || chatId.isBlank()) {
                    client.replyMessage(openMessageId, "重新生成失败：缺少上下文");
                    return;
                }
                Long agentId = config.defaultAgentId() > 0 ? config.defaultAgentId() : null;
                AgentOrchestrator.AgentAnswer answer = orchestrator.answer(chatId, query, agentId);
                String markdown = StreamTokenFixer.fixFull(answer.answer());
                String card = buildCard(query, markdown, answer.sources(), openChatId);
                if (card == null || !client.updateCard(openMessageId, card)) {
                    client.replyMessage(openMessageId, toFeishuText(markdown));   // 更新失败回退纯文本
                }
            }
            case "doc_form_submit" -> {
                // LLM 生成约半分钟，飞书卡片回调 3s 超时即重投——必须异步 + 按 messageId 去重
                if (formInFlight.putIfAbsent(openMessageId, Boolean.TRUE) != null) {
                    log.info("[飞书] 表单提交重复回调（重投），跳过 messageId={}", openMessageId);
                    return;
                }
                String title = textOf(formValue, "title");
                if (title == null || title.isBlank()) {
                    formInFlight.remove(openMessageId);
                    client.replyMessage(openMessageId, "请先填写【标题】再点生成");
                    return;
                }
                final String key = openChatId + ":" + (operator == null ? "anon" : operator);
                final String docType = textOf(formValue, "docType");
                final String points = textOf(formValue, "points");
                final String tone = textOf(formValue, "tone");
                final String issuer = textOf(formValue, "issuer");
                worker.execute(() -> {
                    try {
                        com.zzp.rag.agent.DocWizardService.FormResult result =
                                docWizardService.submitForm(key, docType, title, points, tone, issuer, operator);
                        String url = result.cloudUrl();
                        if (url != null) {
                            String card = buildDocResultCard(title, url);
                            if (card == null || !client.updateCard(openMessageId, card)) {
                                client.replyMessage(openMessageId,
                                        "文档已创建为飞书云文档（在线可编辑）：\n" + url);
                            }
                        } else {
                            // 云文档不可用 → docx 文件消息兜底
                            byte[] file = docWizardService.toDocxFile(title, result.content());
                            String fileKey = client.uploadFile(
                                    title.strip().replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + ".docx", file);
                            if (fileKey != null) {
                                client.sendFileMessage(openChatId, fileKey);
                            }
                            String card = buildDocResultCard(title, null);
                            if (card == null || !client.updateCard(openMessageId, card)) {
                                client.replyMessage(openMessageId,
                                        "已生成《" + title + "》docx 文件并发送到会话。");
                            }
                        }
                    } catch (Exception e) {
                        log.error("[飞书] 表单提交生成失败 chat={}", openChatId, e);
                        client.replyMessage(openMessageId, "生成失败，请重试或直接回复修改要求。");
                    } finally {
                        formInFlight.remove(openMessageId);
                    }
                });
            }
            default -> log.warn("[飞书] 未知卡片动作: {}", action);
        }
    }

    /** 表单字段取值（空串归一为 null） */
    private String textOf(java.util.Map<String, Object> formValue, String name) {
        if (formValue == null) {
            return null;
        }
        Object v = formValue.get(name);
        if (v == null) {
            return null;
        }
        String s = String.valueOf(v).strip();
        return s.isEmpty() ? null : s;
    }

    /** 提取文本并剥掉 @机器人 提及（文本消息里 @ 以 @_user_1 形式出现） */
    private String parseText(String contentJson) {
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            String text = node.path("text").asText(null);
            if (text == null) {
                return "";
            }
            return text.replaceAll("@_user_\\d+", "").replaceAll("\\s+", " ").strip();
        } catch (Exception e) {
            log.debug("[飞书] content 解析失败: {}", e.getMessage());
            return "";
        }
    }

    /** Markdown → 飞书纯文本（text 消息不支持 markdown 渲染）：
     *  剥加粗/斜体星号，## 标题转【】，<br> 转换行、其余 HTML 标签剥除，
     *  表格转可读文本行（分隔行删除、数据行去首尾管道、中间管道换全角 ｜），保留有序编号与换行 */
    private String toFeishuText(String markdown) {
        String text = markdown
                .replaceAll("(?m)^##\\s+(.+)$", "【$1】")
                .replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^[\\s>]*>\\s*", "");
        return flattenTables(text);
    }

    /** 卡片 markdown：飞书卡片 markdown 不支持 GFM 表格（会退化成裸管道符行），
     *  仅展平表格为可读文本，其余 markdown 语法（标题/加粗/列表）保留给卡片渲染；
     *  列表项之间插空行——卡片渲染器对列表支持不稳定，相邻列表项可能黏成一段 */
    private String toCardMarkdown(String markdown) {
        String md = flattenTables(markdown);
        // 缩进编号项 → 子弹点：卡片渲染器不支持嵌套有序列表（缩进丢失后 1,2,1,3 观感错乱），
        // 子项转 "- " 后层级语义直观（外层 1. 2. 3.，子项 - 挂在所属项下）
        md = md.replaceAll("(?m)^(\\s+)(\\d+\\.\\s)", "$1- ");
        // 连续列表项（编号/无序）之间补空行，保证每项独立成段
        md = md.replaceAll("(?m)(^(?:\\d+\\.|[-•🔹])\\s+.*)$(?=\\n(?:\\d+\\.|[-•🔹])\\s+)", "$1\n");
        return md;
    }

    /** 表格 → 可读文本：分隔行（|---|---|）删除、数据行去首尾管道、中间管道换全角 ｜。
     *  空单元格继承上一行同列（模型习惯：重复的部门名只写一次，"| | 陈杰 | …" 补 "研发部"）；
     *  连续表格段的首行视为表头，不向下继承（防止表头列名泄漏进数据行）。 */
    private String flattenTables(String text) {
        StringBuilder sb = new StringBuilder();
        String[] prevCells = null;
        boolean prevIsHeader = false;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.matches("\\|[\\s|:\\-]+\\|")) {
                continue;   // 分隔行（|---|---|）删除
            }
            if (stripped.isEmpty()) {
                sb.append(line).append('\n');
                continue;   // 空行不打断继承链（分隔行删除后留的空行）
            }
            if (stripped.startsWith("|") && stripped.endsWith("|") && stripped.indexOf('|', 1) >= 0) {
                String inner = stripped.substring(1, stripped.length() - 1);
                String[] cells = inner.split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].strip();
                }
                if (prevCells == null) {
                    prevIsHeader = true;   // 段首行 = 表头
                } else if (!prevIsHeader) {
                    for (int i = 0; i < cells.length && i < prevCells.length; i++) {
                        if (cells[i].isEmpty() && !prevCells[i].isEmpty()) {
                            cells[i] = prevCells[i];
                        }
                    }
                } else {
                    prevIsHeader = false;
                }
                prevCells = cells;
                sb.append(String.join(" ｜ ", cells));
            } else {
                prevCells = null;   // 非表格行打断继承链
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }
}
