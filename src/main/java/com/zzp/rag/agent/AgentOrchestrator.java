package com.zzp.rag.agent;

import com.zzp.rag.retrieval.RetrievalPipeline;
import com.zzp.rag.web.StreamTokenFixer;
import dev.langchain4j.model.chat.ChatModel;
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

    private final ChatModel chatModel;
    private final StreamingChatModel streamingChatModel;
    private final ChatModel reasonerModel;
    private final ChatModel contextualModel;
    private final AgentService agentService;
    private final AgentTools agentTools;
    private final RetrievalPipeline retrievalPipeline;

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
            RetrievalPipeline retrievalPipeline) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.reasonerModel = reasonerModel;
        this.contextualModel = contextualModel;
        this.agentService = agentService;
        this.agentTools = agentTools;
        this.retrievalPipeline = retrievalPipeline;
    }

    public record AgentAnswer(String answer, List<String> sources, String route,
                              boolean rerankDegraded, int graphContextChars) {}

    /** 流式回调 — 控制器按此桥接 SSE 事件；onRoute 先于 onToken 同步触发 */
    public interface AgentStreamHandler {
        void onRoute(String route);
        void onToken(String token);
        void onToolExecuted(String toolName, String arguments, boolean retrievalEmpty);
        void onComplete(String route, List<String> sources, boolean rerankDegraded, int graphContextChars);
        void onError(Throwable error);
    }

    // ==================== 非流式 ====================

    public AgentAnswer answer(String conversationId, String query) {
        String route = route(query);
        log.info("[Agent] 路由: {} → {}", query, route);
        if ("CHAT".equals(route)) {
            return new AgentAnswer(chatDirect(query), List.of(), route, false, 0);
        }
        // KNOWLEDGE_QA：确定性预检索——不依赖模型是否调用工具
        // （咨询类问题模型常跳过工具凭自身知识作答，产生引用幻觉；预检索后检索空则直接兜底）
        RetrievalPipeline.PipelineResult pre = retrievalPipeline.execute(query, null);
        boolean retrievalEmpty = pre.segments().isEmpty() && pre.graphContext().isBlank();
        if (retrievalEmpty) {
            log.info("[Agent] 预检索无结果，使用确定性兜底回答");
            return new AgentAnswer(NO_ANSWER, List.of(), route, pre.rerankDegraded(),
                    pre.graphContext().length());
        }
        String context = retrievalPipeline.buildContext(pre, 3000);
        try {
            String answer = agentService.chat(conversationId, enrich(query, context));
            answer = verify(answer, context);
            List<String> sources = agentTools.getSources().isEmpty()
                    ? pre.getSources() : agentTools.getSources();
            return new AgentAnswer(answer, sources, route, pre.rerankDegraded(),
                    pre.graphContext().length());
        } finally {
            agentTools.clear();
        }
    }

    /** 把确定性检索资料拼进用户消息（Agent 直接基于资料回答，工具仅作补充） */
    private String enrich(String query, String context) {
        return query + "\n\n[知识库检索资料]\n" + context + "\n[/知识库检索资料]";
    }

    // ==================== 答案事实校验（编造最后防线） ====================

    private static final String VERIFY_PROMPT = """
            你是答案事实校验器。下面是知识库检索资料和一个基于资料生成的回答。
            逐句检查回答：删除资料中未出现的内容（具体数值、占比、检查项目、药物名称、诊断标准、口诀、延伸服务建议等），只保留资料明确支持的内容；某句只有部分有依据时，只保留有依据的部分。
            【重点】专业术语与英文缩写（检查项目、药物、标志物、基因名等，如 cTnI、ARR、EGFR）必须逐字出现在资料中才能保留，资料中没有的删除或改写为资料中的说法。
            【重点】回答声称来自知识图谱但资料中无对应内容时，删除该声称及相应内容。
            【重点】删除模型的元话语/自我说明（如"需进一步检索""我将检索"等推理过程文字），回答只保留结论性内容。
            不得添加任何新内容，不得改变保留句子的原意，保持原有格式。
            直接输出修订后的回答全文，不要任何解释。
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
    private String verify(String answer, String context) {
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
            String out = contextualModel.chat(VERIFY_PROMPT.formatted(missingBlock, context, answer));
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
            boolean bad = false;
            for (String t : missingTerms) {
                if (sentence.toLowerCase().contains(t.toLowerCase())) {
                    bad = true;
                    break;
                }
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
        return kept.isEmpty() ? NO_ANSWER : kept.toString();
    }

    /** 回答中的英文缩写/带数字术语（全大写、含数字或词内大小写切换，如 ECMO、SGLT2、HFrEF） */
    private static final java.util.regex.Pattern ASCII_TERM =
            java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9+\\-/]{1,12}");

    /** 词内大小写切换（HFrEF、cTnI），排除普通单词（Heart、Smallville） */
    private static final java.util.regex.Pattern MIXED_CASE =
            java.util.regex.Pattern.compile("[A-Z][a-z]+[A-Z]");

    /**
     * 确定性术语核对：提取回答中出现的缩写/带数字术语（ECMO、SGLT2、HFrEF 类），
     * 凡资料中未逐字出现的列入缺失清单（校验器注入 + 硬删除的依据）。
     */
    private List<String> findMissingTerms(String answer, String context) {
        String ctxLower = context.toLowerCase();
        java.util.LinkedHashSet<String> missing = new java.util.LinkedHashSet<>();
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
        if (!missing.isEmpty()) {
            log.info("[Agent] 术语核对缺失 {} 个: {}", missing.size(),
                    String.join("、", missing.stream().limit(12).toList()));
        }
        return List.copyOf(missing);
    }

    /** 校验后的答案按块下发，保持流式观感（按 code point 切，避免拆散 emoji 代理对） */
    private void emitChunks(String text, AgentStreamHandler handler) {
        int i = 0;
        int n = text.length();
        while (i < n) {
            int end = text.offsetByCodePoints(i, Math.min(24, text.codePointCount(i, n)));
            handler.onToken(text.substring(i, end));
            i = end;
        }
    }

    // ==================== 流式 ====================

    public void answerStream(String conversationId, String query, AgentStreamHandler handler) {
        String route = route(query);
        log.info("[Agent] 路由: {} → {}", query, route);
        handler.onRoute(route); // 先于任何 token 同步触发，保证前端先知道路由
        if ("CHAT".equals(route)) {
            streamingChatModel.chat(CHAT_PROMPT.formatted(query), new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    handler.onToken(partialResponse);
                }

                @Override
                public void onCompleteResponse(
                        dev.langchain4j.model.chat.response.ChatResponse completeResponse) {
                    handler.onComplete(route, List.of(), false, 0);
                }

                @Override
                public void onError(Throwable error) {
                    handler.onError(error);
                }
            });
            return;
        }
        // 确定性预检索（同非流式）：检索空直接流式下发兜底句式
        RetrievalPipeline.PipelineResult pre = retrievalPipeline.execute(query, null);
        if (pre.segments().isEmpty() && pre.graphContext().isBlank()) {
            log.info("[Agent] 预检索无结果，流式下发兜底句式");
            handler.onToken(NO_ANSWER);
            handler.onComplete(route, List.of(), pre.rerankDegraded(), 0);
            return;
        }
        String context = retrievalPipeline.buildContext(pre, 3000);
        String enriched = enrich(query, context);

        // 工具执行回调与工具执行同线程，此处读取 ThreadLocal 状态并快照，
        // 供 onCompleteResponse（可能在其他线程）使用
        final List<String>[] sourcesHolder = (List<String>[]) new List[]{pre.getSources()};
        final boolean[] degradedHolder = {pre.rerankDegraded()};
        // 答案校验：服务端先收齐全文 → 对照检索资料核查 → 再下发
        // （token 不再直通前端，换取"无依据内容不出门"的保证；工具事件仍实时透传）
        StringBuffer full = new StringBuffer();
        agentService.chatStream(conversationId, enriched)
                .onPartialResponse(full::append)
                .onToolExecuted(req -> {
                    sourcesHolder[0] = agentTools.getSources();
                    degradedHolder[0] = agentTools.isRerankDegraded();
                    handler.onToolExecuted(req.request().name(), req.request().arguments(), false);
                })
                .onCompleteResponse(resp -> {
                    try {
                        // fixFull 与固定流水线/非流式端点保持一致（### 子标题 → 加粗、- 列表 → 有序）
                        emitChunks(StreamTokenFixer.fixFull(verify(full.toString(), context)), handler);
                        handler.onComplete(route, sourcesHolder[0], degradedHolder[0],
                                pre.graphContext().length());
                    } finally {
                        agentTools.clear();
                    }
                })
                .onError(err -> {
                    try {
                        handler.onError(err);
                    } finally {
                        agentTools.clear();
                    }
                })
                .start();
    }

    // ==================== 意图路由（R1） ====================

    private static final String ROUTER_PROMPT = """
            你是意图路由分类器。判断用户输入属于哪一类，只输出一个词，不要输出任何其他内容：
            - KNOWLEDGE_QA：知识性提问，需要检索资料库或知识图谱才能回答
            - CHAT：闲聊、寒暄、询问你的能力、简单确认、打招呼

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

    private static final String CHAT_PROMPT = """
            你是一个友好的AI客服助手，请简洁自然地回应用户的闲聊、寒暄或关于你能力的问题。

            用户：%s
            """;

    private String chatDirect(String query) {
        return chatModel.chat(CHAT_PROMPT.formatted(query));
    }
}
