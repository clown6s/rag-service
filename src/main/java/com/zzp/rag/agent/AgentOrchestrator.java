package com.zzp.rag.agent;

import com.zzp.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
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
    private final AgentService agentService;
    private final AgentTools agentTools;
    private final RetrievalPipeline retrievalPipeline;

    public AgentOrchestrator(
            @Qualifier("chatModel") ChatModel chatModel,
            @Qualifier("streamingChatModel") StreamingChatModel streamingChatModel,
            @Qualifier("reasonerModel") ChatModel reasonerModel,
            AgentService agentService,
            AgentTools agentTools,
            RetrievalPipeline retrievalPipeline) {
        this.chatModel = chatModel;
        this.streamingChatModel = streamingChatModel;
        this.reasonerModel = reasonerModel;
        this.agentService = agentService;
        this.agentTools = agentTools;
        this.retrievalPipeline = retrievalPipeline;
    }

    public record AgentAnswer(String answer, List<String> sources, String route, boolean rerankDegraded) {}

    /** 流式回调 — 控制器按此桥接 SSE 事件；onRoute 先于 onToken 同步触发 */
    public interface AgentStreamHandler {
        void onRoute(String route);
        void onToken(String token);
        void onToolExecuted(String toolName, String arguments, boolean retrievalEmpty);
        void onComplete(String route, List<String> sources, boolean rerankDegraded);
        void onError(Throwable error);
    }

    // ==================== 非流式 ====================

    public AgentAnswer answer(String conversationId, String query) {
        String route = route(query);
        log.info("[Agent] 路由: {} → {}", query, route);
        if ("CHAT".equals(route)) {
            return new AgentAnswer(chatDirect(query), List.of(), route, false);
        }
        // KNOWLEDGE_QA：确定性预检索——不依赖模型是否调用工具
        // （咨询类问题模型常跳过工具凭自身知识作答，产生引用幻觉；预检索后检索空则直接兜底）
        RetrievalPipeline.PipelineResult pre = retrievalPipeline.execute(query, null);
        boolean retrievalEmpty = pre.segments().isEmpty() && pre.graphContext().isBlank();
        if (retrievalEmpty) {
            log.info("[Agent] 预检索无结果，使用确定性兜底回答");
            return new AgentAnswer(NO_ANSWER, List.of(), route, pre.rerankDegraded());
        }
        String context = retrievalPipeline.buildContext(pre, 3000);
        try {
            String answer = agentService.chat(conversationId, enrich(query, context));
            List<String> sources = agentTools.getSources().isEmpty()
                    ? pre.getSources() : agentTools.getSources();
            return new AgentAnswer(answer, sources, route, pre.rerankDegraded());
        } finally {
            agentTools.clear();
        }
    }

    /** 把确定性检索资料拼进用户消息（Agent 直接基于资料回答，工具仅作补充） */
    private String enrich(String query, String context) {
        return query + "\n\n[知识库检索资料]\n" + context + "\n[/知识库检索资料]";
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
                    handler.onComplete(route, List.of(), false);
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
            handler.onComplete(route, List.of(), pre.rerankDegraded());
            return;
        }
        String context = retrievalPipeline.buildContext(pre, 3000);
        String enriched = enrich(query, context);

        // 工具执行回调与工具执行同线程，此处读取 ThreadLocal 状态并快照，
        // 供 onCompleteResponse（可能在其他线程）使用
        final List<String>[] sourcesHolder = (List<String>[]) new List[]{pre.getSources()};
        final boolean[] degradedHolder = {pre.rerankDegraded()};
        agentService.chatStream(conversationId, enriched)
                .onPartialResponse(handler::onToken)
                .onToolExecuted(req -> {
                    sourcesHolder[0] = agentTools.getSources();
                    degradedHolder[0] = agentTools.isRerankDegraded();
                    handler.onToolExecuted(req.request().name(), req.request().arguments(), false);
                })
                .onCompleteResponse(resp -> {
                    try {
                        handler.onComplete(route, sourcesHolder[0], degradedHolder[0]);
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
