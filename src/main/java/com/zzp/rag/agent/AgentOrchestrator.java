package com.zzp.rag.agent;

import com.zzp.rag.retrieval.RetrievalPipeline;
import com.zzp.rag.web.StreamTokenFixer;import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 编排器 — R1 意图路由 + 分发（状态机极简形态）
 * <p>
 * ROUTE → 意图路由（deepseek-reasoner，纯文本输出分类；R1 不支持 function calling，故用 chat + 解析）
 *   ├─ CHAT         → 直接对话（不检索、不调工具，省一次工具循环开销）
 *   └─ KNOWLEDGE_QA → AgentService 工具循环（是否检索、检索几次由 LLM 决策）
 * 确定性兜底：检索被调用且一无所获时，用固定句式替换 LLM 输出（提示词约束不可靠）。
 */
@Slf4j
@Component
public class AgentOrchestrator {

    /** 检索无结果时的确定性兜底回答（不信任 LLM 输出，见 AgentTools#isRetrievalEmpty） */
    public static final String NO_ANSWER = "根据现有资料，暂无法回答该问题";

    /** 预检索未命中但存在 MCP 技能时的上下文提示（放行 Agent 工具循环，工具无果仍回兜底句式） */
    private static final String MCP_FALLBACK_CONTEXT =
            "文档检索未命中。你可以调用已授权的业务系统工具（如公司制度查询、员工目录）回答；"
                    + "工具也没有结果时输出\"根据现有资料，暂无法回答该问题\"。";

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatModel reasonerModel;
    private final ChatModel contextualModel;
    private final AgentService agentService;
    private final AgentTools agentTools;
    private final RetrievalPipeline retrievalPipeline;
    private final com.zzp.rag.skill.registry.SkillRegistryToolProvider skillRegistryToolProvider;
    private final EmployeeContext employeeContext;
    private final com.zzp.rag.workflow.SopRegistry sopRegistry;
    private final com.zzp.rag.workflow.SopEngine sopEngine;

    /** 答案校验开关：生成后由小模型对照检索资料逐句核查，删除无依据内容（编造的最后防线） */
    @Value("${rag.agent.verify.enabled:true}")
    private boolean verifyEnabled;

    public AgentOrchestrator(
            @Qualifier("chatModel") ChatModel chatModel,
            @Qualifier("streamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("reasonerModel") ChatModel reasonerModel,
            @Qualifier("verifyChatModel") ChatModel contextualModel,
            AgentService agentService,
            AgentTools agentTools,
            RetrievalPipeline retrievalPipeline,
            com.zzp.rag.skill.registry.SkillRegistryToolProvider skillRegistryToolProvider,
            EmployeeContext employeeContext,
            com.zzp.rag.workflow.SopRegistry sopRegistry,
            com.zzp.rag.workflow.SopEngine sopEngine) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.reasonerModel = reasonerModel;
        this.contextualModel = contextualModel;
        this.agentService = agentService;
        this.agentTools = agentTools;
        this.retrievalPipeline = retrievalPipeline;
        this.skillRegistryToolProvider = skillRegistryToolProvider;
        this.employeeContext = employeeContext;
        this.sopRegistry = sopRegistry;
        this.sopEngine = sopEngine;
    }

    public record AgentAnswer(String answer, List<String> sources, String route,
                              boolean rerankDegraded, int graphContextChars, boolean mcpToolsUsed,
                              long employeeId) {}

    /** 流式回调 — 控制器按此桥接 SSE 事件；onRoute 先于 onToken 同步触发 */
    public interface AgentStreamHandler {
        void onRoute(String route);
        void onToken(String token);
        void onToolExecuted(String toolName, String arguments, boolean retrievalEmpty);
        void onComplete(String route, List<String> sources, boolean rerankDegraded,
                        int graphContextChars, boolean mcpToolsUsed, long employeeId);
        void onError(Throwable error);
    }

    // ==================== 非流式 ====================

    public AgentAnswer answer(String conversationId, String query, Long agentId) {
        EmployeeContext.EmployeeRuntime runtime = employeeContext.resolve(agentId);
        // 员工目录确定性直查（最优先：个人信息问法不能被 SOP/路由截胡，
        // 实况："王强是什么时候入职的"被 onboard_guide SOP（关键词"入职"）拦截答了兜底）
        AgentAnswer employeeAnswer = tryEmployeeLookup(query, "KNOWLEDGE_QA", runtime);
        if (employeeAnswer != null) {
            return employeeAnswer;
        }
        // L2 SOP 优先：确定性流程命中直接执行（该严格的地方严格；失败回退 L3 常规路径）
        com.zzp.rag.workflow.SopDefinition sop = sopRegistry.match(query);
        if (sop != null) {
            try {
                return runSop(sop, query, runtime);
            } catch (Exception e) {
                log.warn("[SOP] {} 执行失败，回退常规路径: {}", sop.code(), e.getMessage());
            }
        }
        String route = route(query);
        log.info("[Agent] 路由: {} → {}", query, route);
        if ("CHAT".equals(route)) {
            return new AgentAnswer(chatDirect(query, runtime), List.of(), route, false, 0, false,
                    runtime != null ? runtime.employeeId() : 0);
        }
        // KNOWLEDGE_QA：确定性预检索——不依赖模型是否调用工具
        // （咨询类问题模型常跳过工具凭自身知识作答，产生引用幻觉；预检索后检索空则直接兜底。
        //   例外：员工有启用 MCP 技能时放行 Agent 工具循环，让业务工具获得回答机会。
        //   阶段 4：预检索与工具装配均按该员工 knowledgeScope/授权生效）
        employeeContext.bind(conversationId, runtime);
        try {
            // 带 knowledgeScope 的员工禁用图谱遍历：图谱是全局实体关系，范围过滤罩不住
            boolean graphAllowed = runtime == null || !runtime.hasScope();
            RetrievalPipeline.PipelineResult pre = retrievalPipeline.execute(
                    query, runtime != null ? runtime.scopeExpr() : null, graphAllowed);
            boolean mcpSkillsAvailable = skillRegistryToolProvider.mcpSkillsEnabled(
                    runtime != null ? runtime.employeeId() : -1);
            // 有 MCP 工具的员工：图谱不算命中——图谱-only 对业务问题纯噪音
            // （实况："我们公司有几个人"被图谱的"公司→实施犯罪"关系截胡，模型没去调工具），
            // 文档无命中直接放行工具循环，让业务工具获得回答机会
            boolean retrievalEmpty = mcpSkillsAvailable
                    ? pre.segments().isEmpty()
                    : pre.segments().isEmpty() && pre.graphContext().isBlank();
            if (retrievalEmpty && !mcpSkillsAvailable) {
                log.info("[Agent] 预检索无结果，使用确定性兜底回答");
                return new AgentAnswer(NO_ANSWER, List.of(), route, pre.rerankDegraded(),
                        pre.graphContext().length(), false,
                        runtime != null ? runtime.employeeId() : 0);
            }
            String context = retrievalEmpty
                    ? MCP_FALLBACK_CONTEXT
                    : retrievalPipeline.buildContext(pre, 3000);
            // 图谱-only：文档片段全被阈值过滤、仅图谱非空（授权过宽重灾区，需收紧）
            boolean graphOnly = !retrievalEmpty && pre.segments().isEmpty() && !pre.graphContext().isBlank();
            String answer = agentService.chat(conversationId, enrich(query, context, graphOnly, runtime));
            boolean mcpUsed = com.zzp.rag.mcp.McpCallTracker.consumeCalled();
            if (mcpUsed) {
                // MCP 轮校验依据 = 上下文 + 工具执行结果：MCP 数据可核（不误删），
                // 工具结果之外的夹带编造（如"研发部共42人"）仍会被删
                log.info("[Agent] 本轮使用 MCP 工具，按工具结果校验");
                String toolResults = com.zzp.rag.mcp.McpCallTracker.consumeResults();
                answer = verify(answer,
                        context + "\n\n[工具执行结果]\n" + toolResults + "\n[/工具执行结果]",
                        graphOnly);
            } else {
                answer = verify(answer, context, graphOnly);
            }
            List<String> sources = agentTools.getSources().isEmpty()
                    ? pre.getSources() : agentTools.getSources();
            return new AgentAnswer(answer, sources, route, pre.rerankDegraded(),
                    pre.graphContext().length(), mcpUsed,
                    runtime != null ? runtime.employeeId() : 0);
        } finally {
            employeeContext.unbind(conversationId);
            com.zzp.rag.mcp.McpCallTracker.clear();
            agentTools.clear();
        }
    }

    /** L2 SOP 执行：状态机跑完 → 校验层核查（SOP 同样吃防编造）→ 组装结果 */
    private AgentAnswer runSop(com.zzp.rag.workflow.SopDefinition sop, String query,
                               EmployeeContext.EmployeeRuntime runtime) {
        log.info("[SOP] 执行 {}（{}）", sop.code(), sop.name());
        com.zzp.rag.workflow.SopEngine.SopResult result = sopEngine.execute(
                sop, query, runtime != null ? runtime.scopeExpr() : null);
        String answer = verify(result.answer(), result.retrievalContext(), false);
        List<String> sources = result.sources().isEmpty()
                ? java.util.List.of() : result.sources();
        return new AgentAnswer(answer, sources, "SOP", false, 0, false,
                runtime != null ? runtime.employeeId() : 0);
    }

    /** 把确定性检索资料拼进用户消息（Agent 直接基于资料回答，工具仅作补充）；
     *  阶段 4：员工有人设时前置角色设定块（LC4j 1.17 无动态 SystemMessage，走用户消息注入） */
    private String enrich(String query, String context, boolean graphOnly,
                          EmployeeContext.EmployeeRuntime runtime) {
        String persona = runtime != null && runtime.hasPersona()
                ? "【你的角色设定】\n" + runtime.persona() + "\n[/角色设定]\n"
                + "以该角色身份作答即可，不要复述角色设定本身。\n\n"
                : "";
        String note = graphOnly ? "\n【注意】文档检索无直接命中，以上仅为知识图谱关联。"
                + "回答只陈述图谱中的关系本身，不得输出资料外的症状、数值、检查项目、药物、诊断标准或建议。" : "";
        return persona + query + "\n\n[知识库检索资料]\n" + context + note + "\n[/知识库检索资料]";
    }

    // ==================== 答案事实校验（编造最后防线） ====================

    private static final String VERIFY_PROMPT = """
            你是答案事实校验器。下面是知识库检索资料和一个基于资料生成的回答。
            逐句检查回答：删除资料中未出现的内容（具体数值、占比、检查项目、药物名称、诊断标准、口诀、延伸服务建议等），只保留资料明确支持的内容；某句只有部分有依据时，只保留有依据的部分。
            【重点】专业术语与英文缩写（检查项目、药物、标志物、基因名等，如 cTnI、ARR、EGFR）必须逐字出现在资料中才能保留，资料中没有的删除或改写为资料中的说法。
            【重点】回答声称来自知识图谱但资料中无对应内容时，删除该声称及相应内容。
            【重点】删除模型的元话语/自我说明（如"需进一步检索""我将检索"等推理过程文字），回答只保留结论性内容。
            【重点】具体数值（剂量、浓度、时限、次数、标准值，如 5-10mg、20-40mg、4h、140/90mmHg）必须逐字原样保留，不得改写、近似或删除；含数值的句子若无依据则整句删除，禁止压缩成无数值残句（如把"吗啡5-10mg静脉注射"压成"吗啡静脉注射"）。
            【重点】删除循环定义/同义反复句（如"右心衰竭以右心衰为特征"这类不提供新信息的句子）。
            不得添加任何新内容，不得改变保留句子的原意，保持原有格式。
            直接输出修订后的回答全文，不要任何解释。
            %s
            %s

            [知识库检索资料]
            %s
            [/知识库检索资料]

            [待校验回答]
            %s
            """;

    /**
     * 生成后校验：小模型对照检索资料逐句核查，删除无依据内容。
     * 前置确定性核对：提取回答中的大写缩写/带数字术语，凡资料中未逐字出现的
     * 生成"已知缺失术语"清单注入校验器（小模型术语级漏检率高，用确定性手段补）。
     * 后置确定性兜底：校验器软约束失效时（漏删），硬删除仍含缺失术语的句子。
     * 软依赖：校验失败或返回空时按原文下发，不阻断链路。
     */
    private String verify(String answer, String context, boolean graphOnly) {
        if (!verifyEnabled || answer == null || answer.isBlank()
                || context == null || context.isBlank() || NO_ANSWER.equals(answer)) {
            return answer;
        }
        try {
            List<String> missing = findMissingTerms(answer, context);
            String missingBlock = missing.isEmpty() ? "" :
                    "【已知缺失术语】以下术语在资料中不存在："
                            + String.join("、", missing.stream().limit(12).toList())
                            + "。包含它们的句子必须删除或改写（移除术语本身），不得保留。";
            String graphOnlyRule = graphOnly ? "【重点】本次检索无文档片段命中，仅提供知识图谱关联。"
                    + "回答中超出图谱关系本身的内容（症状、数值、检查项目、药物、诊断标准、建议等）全部删除。" : "";
            String out = contextualModel.chat(VERIFY_PROMPT.formatted(missingBlock, graphOnlyRule, context, answer));
            if (out == null || out.isBlank()) {
                return answer;
            }
            String cleaned = out.strip();
            if (cleaned.startsWith("```")) {
                cleaned = cleaned.replaceFirst("^```\\w*\\s*", "").replaceFirst("\\s*```$", "");
            }
            if (cleaned.isBlank()) {
                return answer;
            }
            if (!cleaned.equals(answer)) {
                log.info("[Agent] 答案校验已修订: {} 字 → {} 字", answer.length(), cleaned.length());
            }
            return stripMissingTermSentences(cleaned, missing);
        } catch (Exception e) {
            log.warn("[Agent] 答案校验失败，按原文下发: {}", e.getMessage());
            return answer;
        }
    }

    /**
     * 确定性硬删除：校验器（小模型）漏删时，凡含"资料中不存在术语"的句子直接移除。
     * 术语不在资料中是确定性算出来的事实，含它的句子必然无依据——不再依赖模型自觉。
     * 全部句子被删时返回兜底句式（回答整体无依据）。
     */
    private String stripMissingTermSentences(String text, List<String> missingTerms) {
        if (missingTerms.isEmpty() || text == null || text.isBlank()) {
            return text;
        }
        StringBuilder kept = new StringBuilder();
        int removed = 0;
        for (String sentence : text.split("(?<=[。！？；\\n])")) {
            String normSentence = normalizeForCompare(sentence);
            boolean bad = false;
            for (String t : missingTerms) {
                if (normSentence.contains(normalizeForCompare(t))) {
                    bad = true;
                    break;
                }
            }
            // 延伸服务/元话语句式（提示词禁但模型偶犯，确定性拦截）
            if (!bad && EXTENSION_OFFER.matcher(sentence).find()) {
                bad = true;
            }
            if (bad) {
                removed++;
            } else {
                kept.append(sentence);
            }
        }
        if (removed == 0) {
            return text;
        }
        log.info("[Agent] 确定性删除含缺失术语句子 {} 句（术语: {}）",
                removed, String.join("、", missingTerms.stream().limit(8).toList()));
        String result = kept.toString().isBlank() ? NO_ANSWER : kept.toString();
        return collapseEmptyHeadings(result);
    }

    /**
     * 硬删后清理：内容被删光只剩标题时（"✅ **基础药物治疗**：" 下空无一物），移除空标题行。
     * 判定：标题行（加粗行或 ✅/📌/⚠️ 开头）之后直到下一个标题或文末全为空白。
     */
    private String collapseEmptyHeadings(String text) {
        if (text == null || text.isBlank() || NO_ANSWER.equals(text)) {
            return text;
        }
        String result = text.replaceAll(
                "(?m)^[ \\t]*(?:✅|📌|⚠️)?[ \\t]*\\*\\*[^*\\n]{1,40}\\*\\*[ \\t]*[：:]?[ \\t]*\\n"
                        + "(?=(?:[ \\t]*\\n)*[ \\t]*(?:✅|📌|⚠️|\\*\\*|\\d+[.、]|[一二三四五六七八九十]+[、.]|$))",
                "");
        // 压缩连续空行（删除后遗留）
        return result.replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** 回答中的英文缩写/带数字术语（全大写、含数字或词内大小写切换，如 ECMO、SGLT2、HFrEF） */
    private static final java.util.regex.Pattern ASCII_TERM =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+\\-/]{1,12}");

    /** 词内大小写切换（HFrEF、cTnI），排除普通单词（Heart、Smallville） */
    private static final java.util.regex.Pattern MIXED_CASE =
            java.util.regex.Pattern.compile("[A-Z][a-z]+[A-Z]");

    /** 中文药物名后缀（覆盖常用药物类别：XX昔布/XX洛尔/XX沙坦/XX霉素等；丁胺/那非/坦 覆盖多巴酚丁胺、西地那非、马昔腾坦类） */
    private static final java.util.regex.Pattern CN_DRUG = java.util.regex.Pattern.compile(
            "[\\u4e00-\\u9fa5]{1,6}(霉素|昔布|洛尔|普利|沙坦|他汀|地平|西汀|唑|米松|磺吡啶|环素|西林|替尼|单抗|洛芬|尼酮|格列酮|丁胺|那非|坦|卡尼|前列素|双胍)");

    /**
     * 高危中文短语词表：模型通用知识补全的重灾区（生活方式干预类具体表述——
     * 实况："高血压的预防"答出"每周2次力量训练/胰岛素敏感性/正念冥想"等
     * 内科学高血压内容里根本没有的说法，小模型软校验全部放行）。
     * 词表内短语必须逐字出现在检索资料中，否则删除所在句。观察漏网即加词。
     */
    private static final List<String> CN_TERMS = List.of(
            "力量训练", "胰岛素敏感性", "心理平衡", "正念冥想", "深呼吸训练",
            "血管弹性", "交感神经", "动态血压", "睡眠不足");

    /** 数值+单位模式（"140 mmHg""30分钟""15：35岁""约30%""23°C"等，分隔符归一化后比对资料） */
    private static final java.util.regex.Pattern NUM_UNIT = java.util.regex.Pattern.compile(
            "\\d+(?:[.：:、～~\\-]\\d+)?\\s*(?:mmHg|mmol/L|kg/m²|°C|℃|mg|ml|g|kg|cm|分钟|小时|天|周|月|年|岁|%|次|度)");

    /** 人名+引文模式（"李煜"帘卷西风…""——引文在资料里、作者不在的伪造归因） */
    private static final java.util.regex.Pattern NAME_QUOTE = java.util.regex.Pattern.compile(
            "([\\u4e00-\\u9fa5]{2,3})[“\"][^”\"]{2,30}[”\"]");

    /**
     * 高危跨主题术语锚定表：术语在库内出现，但被模型套用到错误主题时（实例如
     * "终身监禁"在刑法里只属于贪污/受贿，被套到抢劫上）→ 要求上下文中该术语
     * 出现位置附近（±150 字窗口）必须出现锚定词，否则视为缺失术语（硬删该句）。
     * 新增词条：术语 + 其合法语境锚定词。
     */
    private static final java.util.Map<String, List<String>> ANCHORED_TERMS = java.util.Map.of(
            "终身监禁", List.of("贪污", "受贿"));

    private static final int ANCHOR_WINDOW = 150;

    /** 数值比对归一化：小写、去空白、全角/半角分隔符统一为 '-'（答案经 fixToken 把 – — 转 ：，资料里是原样） */
    private static String normalizeForCompare(String text) {
        return text.toLowerCase()
                .replaceAll("\\s+", "")
                .replaceAll("[：:、～~–—]", "-");
    }

    /**
     * 高危术语锚定：术语必须出现在上下文中，且至少一处出现的 ±150 字窗口内含锚定词。
     * 术语根本不在上下文 → false（无依据）；出现但无锚定词 → false（跨主题套用）。
     */
    private static boolean termAnchored(String context, String term, List<String> anchors) {
        int idx = context.indexOf(term);
        if (idx < 0) {
            return false;
        }
        while (idx >= 0) {
            int start = Math.max(0, idx - ANCHOR_WINDOW);
            int end = Math.min(context.length(), idx + term.length() + ANCHOR_WINDOW);
            String window = context.substring(start, end);
            if (anchors.stream().anyMatch(window::contains)) {
                return true;
            }
            idx = context.indexOf(term, idx + term.length());
        }
        return false;
    }

    /** 延伸服务/自我推销句式（"欢迎告诉我方向""我可为您""请确认是否需要检索"——提示词禁但模型偶犯） */
    private static final java.util.regex.Pattern EXTENSION_OFFER = java.util.regex.Pattern.compile(
            "欢迎随时|欢迎告诉|如需我|需要我帮|我可以为你|我可为您|我可为你|请确认是否"
                    + "|随时提出|可立即为你|请告诉我您最关注|您希望优先了解"
                    + "|以下(是|为).{0,12}(摘要|总结)|摘要如下|核心内容如下"
                    + "|是否需要|如您能提供|建议您(通过|向|咨询|联系|查看|使用)"
                    + "|知识图谱中未|资料中未包含"
                    + "|欢迎继续|如需(个性化|更多|进一步|定制)");

    /**
     * 确定性术语核对：提取回答中出现的缩写/带数字术语/中文药物名/数值单位，
     * 凡资料中未逐字出现的列入缺失清单（校验器注入 + 硬删除的依据）。
     */
    private List<String> findMissingTerms(String answer, String context) {
        String ctxLower = context.toLowerCase();
        String normCtx = normalizeForCompare(context);
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
        // 1. ASCII 缩写/带数字术语
        java.util.regex.Matcher m = ASCII_TERM.matcher(answer);
        while (m.find()) {
            String t = m.group();
            if (t.length() < 2) {
                continue;
            }
            boolean risky = t.chars().allMatch(c -> Character.isUpperCase(c) || Character.isDigit(c) || c == '+' || c == '/' || c == '-')
                    || t.chars().anyMatch(Character::isDigit)
                    || MIXED_CASE.matcher(t).find();
            if (risky && !ctxLower.contains(t.toLowerCase())) {
                missing.add(t);
            }
        }
        // 2. 中文药物名（后缀词表）
        java.util.regex.Matcher dm = CN_DRUG.matcher(answer);
        while (dm.find()) {
            String t = dm.group();
            if (!ctxLower.contains(t)) {
                missing.add(t);
            }
        }
        // 3. 数值+单位：多位数本体比对（兼容"2022年1月10日"vs"2022-01-10"的格式差异——
        //    历史坑：整串字面比对把合法中文日期误判缺失而整句误删；单位/单位数不作为缺失依据）
        java.util.regex.Matcher nm = NUM_UNIT.matcher(answer);
        while (nm.find()) {
            String t = nm.group();
            java.util.regex.Matcher digitRun = java.util.regex.Pattern.compile("\\d{2,}").matcher(t);
            while (digitRun.find()) {
                String d = digitRun.group();
                if (!normCtx.contains(d)) {
                    missing.add(d);
                }
            }
        }
        // 4. 人名+引文：引文本身可能在资料中，作者名不在 → 伪造归因（李煜/李清照类错误）
        java.util.regex.Matcher qm = NAME_QUOTE.matcher(answer);
        while (qm.find()) {
            String name = qm.group(1);
            if (!ctxLower.contains(name)) {
                missing.add(name);
            }
        }
        // 5. 高危术语锚定核对：术语在上下文出现的位置附近必须出现锚定词，
        //    否则视为跨主题套用（"终身监禁"套到抢劫）→ 缺失
        for (java.util.Map.Entry<String, List<String>> e : ANCHORED_TERMS.entrySet()) {
            if (answer.contains(e.getKey()) && !termAnchored(context, e.getKey(), e.getValue())) {
                missing.add(e.getKey());
                log.info("[Agent] 高危术语锚定失败: {}（语境应含 {}）",
                        e.getKey(), String.join("/", e.getValue()));
            }
        }
        // 6. 高危中文短语：词表内短语必须逐字出现在资料中（通用知识补全重灾区）
        for (String t : CN_TERMS) {
            if (answer.contains(t) && !ctxLower.contains(t)) {
                missing.add(t);
                log.info("[Agent] 高危中文短语缺失: {}", t);
            }
        }
        if (!missing.isEmpty()) {
            log.info("[Agent] 术语核对缺失 {} 个: {}", missing.size(),
                    String.join("、", missing.stream().limit(12).toList()));
        }
        return List.copyOf(missing);
    }

    /**
     * 校验后的答案按块下发，保持流式观感：
     * 按 code point 切块（避免拆散 emoji 代理对）+ 块间节流
     * （校验后全文已就绪，无节流会瞬间发完，客户端渲染成"一次性出现"）。
     */
    private void emitChunks(String text, AgentStreamHandler handler) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            int end = text.offsetByCodePoints(i, Math.min(24, text.codePointCount(i, n)));
            handler.onToken(text.substring(i, end));
            i = end;
            try {
                Thread.sleep(40);   // 回放节奏 ≈ 600 字/秒，接近真实生成速度
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    // ==================== 流式 ====================

    public void answerStream(String conversationId, String query, Long agentId, AgentStreamHandler handler) {
        EmployeeContext.EmployeeRuntime runtime = employeeContext.resolve(agentId);
        // 员工目录确定性直查（最优先：个人信息问法不能被 SOP/路由截胡，
        // 实况："王强是什么时候入职的"被 onboard_guide SOP（关键词"入职"）拦截答了兜底）
        AgentAnswer employeeAnswer = tryEmployeeLookup(query, "KNOWLEDGE_QA", runtime);
        if (employeeAnswer != null) {
            handler.onRoute(employeeAnswer.route());
            emitChunks(StreamTokenFixer.fixFull(employeeAnswer.answer()), handler);
            handler.onComplete(employeeAnswer.route(), employeeAnswer.sources(), false, 0,
                    false, employeeAnswer.employeeId());
            return;
        }
        // L2 SOP 优先（同非流式）：同步执行后分块回放，保持流式观感
        com.zzp.rag.workflow.SopDefinition sop = sopRegistry.match(query);
        if (sop != null) {
            try {
                AgentAnswer sopAnswer = runSop(sop, query, runtime);
                handler.onRoute("SOP");
                emitChunks(StreamTokenFixer.fixFull(sopAnswer.answer()), handler);
                handler.onComplete("SOP", sopAnswer.sources(), false, 0, false,
                        runtime != null ? runtime.employeeId() : 0);
                return;
            } catch (Exception e) {
                log.warn("[SOP] {} 流式执行失败，回退常规路径: {}", sop.code(), e.getMessage());
            }
        }
        String route = route(query);
        log.info("[Agent] 路由: {} → {}", query, route);
        handler.onRoute(route); // 先于任何 token 同步触发，保证前端先知道路由
        if ("CHAT".equals(route)) {
            String prompt = chatPrompt(query, runtime);
            streamingChatModel.chat(prompt, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    handler.onToken(partialResponse);
                }

                @Override
                public void onCompleteResponse(
                        dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                    handler.onComplete(route, List.of(), false, 0, false,
                            runtime != null ? runtime.employeeId() : 0);
                }

                @Override
                public void onError(Throwable error) {
                    handler.onError(error);
                }
            });
            return;
        }
        // 确定性预检索（同非流式）：检索空直接流式下发兜底句式；
        // 例外：员工有启用 MCP 技能时放行 Agent 工具循环（业务工具获得回答机会）。
        // 阶段 4：绑定员工上下文后，工具装配按 conversationId 反查该员工授权/范围
        employeeContext.bind(conversationId, runtime);
        // 带 knowledgeScope 的员工禁用图谱遍历：图谱是全局实体关系，范围过滤罩不住
        boolean graphAllowed = runtime == null || !runtime.hasScope();
        RetrievalPipeline.PipelineResult pre = retrievalPipeline.execute(
                query, runtime != null ? runtime.scopeExpr() : null, graphAllowed);
        boolean mcpSkillsAvailable = skillRegistryToolProvider.mcpSkillsEnabled(
                runtime != null ? runtime.employeeId() : -1);
        // 有 MCP 工具的员工：图谱不算命中（同非流式，图谱-only 会截胡业务工具循环）
        boolean retrievalEmpty = mcpSkillsAvailable
                ? pre.segments().isEmpty()
                : pre.segments().isEmpty() && pre.graphContext().isBlank();
        if (retrievalEmpty && !mcpSkillsAvailable) {
            log.info("[Agent] 预检索无结果，流式下发兜底句式");
            employeeContext.unbind(conversationId);
            handler.onToken(NO_ANSWER);
            handler.onComplete(route, List.of(), pre.rerankDegraded(), 0, false,
                    runtime != null ? runtime.employeeId() : 0);
            return;
        }
        String context = retrievalEmpty ? MCP_FALLBACK_CONTEXT
                : retrievalPipeline.buildContext(pre, 3000);
        boolean graphOnly = !retrievalEmpty && pre.segments().isEmpty() && !pre.graphContext().isBlank();
        String enriched = enrich(query, context, graphOnly, runtime);

        // 工具执行回调与工具执行同线程，此处读取 ThreadLocal 状态并快照，
        // 供 onCompleteResponse（可能在其他线程）使用
        final List<String>[] sourcesHolder = (List<String>[]) new List[]{pre.getSources()};
        final boolean[] degradedHolder = {pre.rerankDegraded()};
        final boolean[] mcpUsedHolder = {false};
        final StringBuffer mcpResultsHolder = new StringBuffer();
        // 答案校验：服务端先收齐全文 → 对照检索资料核查 → 再下发
        // （token 不再直通前端，换取"无依据内容不出门"的保证；工具事件仍实时透传；
        //   本轮用过 MCP 工具则按"上下文+工具结果"校验——MCP 数据可核，夹带编造仍删）
        StringBuffer full = new StringBuffer();
        try {
            agentService.chatStream(conversationId, enriched)
                    .onPartialResponse(full::append)
                    .onToolExecuted(req -> {
                        sourcesHolder[0] = agentTools.getSources();
                        degradedHolder[0] = agentTools.isRerankDegraded();
                        mcpUsedHolder[0] |= com.zzp.rag.mcp.McpCallTracker.consumeCalled();
                        mcpResultsHolder.append(com.zzp.rag.mcp.McpCallTracker.consumeResults());
                        handler.onToolExecuted(req.request().name(), req.request().arguments(), false);
                    })
                    .onCompleteResponse(resp -> {
                        try {
                            String finalAnswer = mcpUsedHolder[0]
                                    ? verify(full.toString(),
                                            context + "\n\n[工具执行结果]\n" + mcpResultsHolder
                                                    + "\n[/工具执行结果]",
                                            graphOnly)
                                    : verify(full.toString(), context, graphOnly);
                            // fixFull 与固定流水线/非流式端点保持一致（### 子标题 → 加粗、- 列表 → 有序）
                            emitChunks(StreamTokenFixer.fixFull(finalAnswer), handler);
                            handler.onComplete(route, sourcesHolder[0], degradedHolder[0],
                                    pre.graphContext().length(), mcpUsedHolder[0],
                                    runtime != null ? runtime.employeeId() : 0);
                        } finally {
                            employeeContext.unbind(conversationId);
                            com.zzp.rag.mcp.McpCallTracker.clear();
                            agentTools.clear();
                        }
                    })
                    .onError(err -> {
                        try {
                            handler.onError(err);
                        } finally {
                            employeeContext.unbind(conversationId);
                            com.zzp.rag.mcp.McpCallTracker.clear();
                            agentTools.clear();
                        }
                    })
                    .start();
        } catch (Exception e) {
            // chatStream 同步阶段异常（如记忆存取失败）——解绑并上报，防绑定泄漏
            employeeContext.unbind(conversationId);
            handler.onError(e);
        }
    }

    /** 个人信息关键词（员工目录直查触发条件） */
    private static final java.util.regex.Pattern EMPLOYEE_INFO_WORDS =
            java.util.regex.Pattern.compile("入职|工号|部门|职位|岗位|什么时候|何时|哪一年|薪资|工资");

    /** 人数统计问法（"我们公司有多少人"/"研发部有几个人"） */
    private static final java.util.regex.Pattern HEADCOUNT =
            java.util.regex.Pattern.compile("多少人|有几个人|有几名|几名员工|员工人数|员工总数|人数是多少");

    /**
     * 员工目录确定性直查：不依赖 LLM 是否调用 MCP 工具（工具决策不稳定，实况：
     * "王强是什么时候入职的"被图谱带偏未调 employee_lookup、"我们公司有多少人"
     * 未调 employee_directory，均答了兜底），在 SOP/路由之前拦截。
     * 三种模式：人数统计（全公司/部门级）、员工个人信息（姓名 + 关键词）。
     * 未启用 MCP 技能的员工不生效；未命中返回 null 走常规路径。
     */
    private AgentAnswer tryEmployeeLookup(String query, String route,
                                          EmployeeContext.EmployeeRuntime runtime) {
        try {
            Long employeeId = runtime != null ? runtime.employeeId() : -1;
            if (!skillRegistryToolProvider.mcpSkillsEnabled(employeeId)) {
                return null;
            }
            // 人数统计问法（确定性，含部门级："研发部有几个人"）
            if (HEADCOUNT.matcher(query).find()) {
                String dept = null;
                for (java.util.Map<String, String> emp : com.zzp.rag.mcp.demo.DemoMcpData.EMPLOYEES) {
                    if (query.contains(emp.get("dept"))) {
                        dept = emp.get("dept");
                        break;
                    }
                }
                // 数据驱动计数（directory() 的返回带表头行，lines().count() 会多数 1）
                final String deptName = dept;
                int count = deptName == null
                        ? com.zzp.rag.mcp.demo.DemoMcpData.EMPLOYEES.size()
                        : (int) com.zzp.rag.mcp.demo.DemoMcpData.EMPLOYEES.stream()
                                .filter(e -> e.get("dept").equals(deptName)).count();
                String listing = com.zzp.rag.mcp.demo.DemoMcpData.directory(dept);
                if (dept == null) {
                    listing = listing.replaceFirst("全体员工名单：\\s*", "");   // 表头与答案首句重复
                }
                String answer = dept != null
                        ? dept + "共有 " + count + " 人：\n" + listing
                        : "根据员工目录，公司共有 " + count + " 位员工：\n" + listing;
                log.info("[Agent] 员工目录直查（人数统计 dept={}）确定性作答", dept);
                answer = answer.strip();
                return new AgentAnswer(answer, List.of(), route, false, 0, true, employeeId);
            }
            if (!EMPLOYEE_INFO_WORDS.matcher(query).find()) {
                return null;
            }
            java.util.List<String> hits = new java.util.ArrayList<>();
            for (java.util.Map<String, String> emp : com.zzp.rag.mcp.demo.DemoMcpData.EMPLOYEES) {
                if (query.contains(emp.get("name"))) {
                    hits.add(com.zzp.rag.mcp.demo.DemoMcpData.lookupEmployee(emp.get("name")));
                }
            }
            if (hits.isEmpty()) {
                return null;
            }
            String answer = hits.size() == 1
                    ? hits.get(0) + "。"
                    : String.join("\n", hits);
            log.info("[Agent] 员工目录直查命中 {} 人，确定性作答", hits.size());
            return new AgentAnswer(answer, List.of(), route, false, 0, true, employeeId);
        } catch (Exception e) {
            log.warn("[Agent] 员工目录直查异常，回退常规路径: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 意图路由（R1） ====================

    private static final String ROUTER_PROMPT = """
            你是意图路由分类器。判断用户输入属于哪一类，只输出一个词，不要输出任何其他内容：
            - KNOWLEDGE_QA：知识性提问，需要检索资料库或知识图谱才能回答
            - CHAT：闲聊、寒暄、询问你的能力、简单确认、打招呼、询问天气/时间等实时信息（资料库无法回答）

            用户输入：
            """;

    private String route(String query) {
        try {
            String out = reasonerModel.chat(ROUTER_PROMPT + query);
            String cleaned = out == null ? "" : out.trim().toUpperCase();
            if (cleaned.contains("CHAT")) {
                return "CHAT";
            }
            return "KNOWLEDGE_QA";
        } catch (Exception e) {
            // 路由失败默认走 Agent 工具循环（Agent 也能处理闲聊，只是多一次决策开销）
            log.warn("[Agent] 路由调用失败，默认 KNOWLEDGE_QA: {}", e.getMessage());
            return "KNOWLEDGE_QA";
        }
    }

    // ==================== 闲聊直达 ====================

    private static final String CHAT_PROMPT_BASE = """
            你是一个友好的AI客服助手，请简洁自然地回应用户的闲聊、寒暄或关于你能力的问题。
            你没有实时数据（天气、股价、时间等），涉及此类问题时如实说明无法获取，不得编造具体数值。
            """;

    private static final String CHAT_PROMPT = CHAT_PROMPT_BASE + "\n用户：%s";

    /** 闲聊提示词（阶段 4：员工有人设时注入角色，保持人设一致性） */
    private String chatPrompt(String query, EmployeeContext.EmployeeRuntime runtime) {
        if (runtime == null || !runtime.hasPersona()) {
            return CHAT_PROMPT.formatted(query);
        }
        return (CHAT_PROMPT_BASE + "\n你的角色设定：%s\n以该角色身份回应即可，不要复述角色设定本身。\n\n用户：%s")
                .formatted(runtime.persona(), query);
    }

    private String chatDirect(String query, EmployeeContext.EmployeeRuntime runtime) {
        return chatModel.chat(chatPrompt(query, runtime));
    }
}
