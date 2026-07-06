package com.jiadaojia.rag.web;

import com.jiadaojia.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.data.message.AiMessage;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * RAG 对话 API
 *
 * POST /api/rag/chat         — 普通对话
 * POST /api/rag/chat/stream  — SSE 流式对话
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
@RequiredArgsConstructor
public class RagChatController {

    private final RetrievalPipeline retrievalPipeline;
    private final ChatLanguageModel chatModel;
    private final StreamingChatLanguageModel streamingChatModel;

    private final ExecutorService executor = Executors.newCachedThreadPool();

    // ==================== 普通对话 ====================

    @PostMapping("/chat")
    public ResponseEntity<ChatResponse> chat(@RequestBody ChatRequest req) {
        // 检索
        var result = retrievalPipeline.execute(req.query(), null);
        String context = retrievalPipeline.buildContext(result, 3000);

        // 生成
        String prompt = buildPrompt(context, req.query());
        String answer = chatModel.generate(prompt);

        return ResponseEntity.ok(new ChatResponse(answer, result.getSources()));
    }

    // ==================== SSE 流式对话 ====================

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@RequestBody ChatRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L); // 5分钟超时

        executor.execute(() -> {
            try {
                // 1. 检索
                var result = retrievalPipeline.execute(req.query(), null);
                String context = retrievalPipeline.buildContext(result, 3000);

                // 2. 先发送引用来源
                emitter.send(SseEmitter.event()
                        .name("sources")
                        .data(result.getSources()));

                // 3. 流式生成
                String prompt = buildPrompt(context, req.query());
                streamingChatModel.generate(prompt, new StreamingResponseHandler<AiMessage>() {
                    @Override
                    public void onNext(String token) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("token")
                                    .data(token));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onComplete(Response<AiMessage> response) {
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data("[DONE]"));
                            emitter.complete();
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable t) {
                        log.error("[SSE] 生成失败", t);
                        emitter.completeWithError(t);
                    }
                });
            } catch (Exception e) {
                log.error("[SSE] 检索失败", e);
                emitter.completeWithError(e);
            }
        });

        // 客户端断开时清理
        emitter.onCompletion(() -> log.debug("[SSE] 客户端断开"));
        emitter.onTimeout(() -> log.debug("[SSE] 超时"));

        return emitter;
    }

    /**
     * 构建 RAG Prompt
     */
    private String buildPrompt(String context, String query) {
        return """
            你是一个专业的AI客服助手。请基于以下参考资料回答用户问题。
            
            ## 规则
            - 逐条引用来源，格式：[来源N]
            - 如果参考资料不足以回答问题，明确告知"根据现有资料，暂无法回答该问题"
            - 回答简洁准确，不添加猜测性内容
            
            ## 参考资料
            %s
            
            ## 用户问题
            %s
            """.formatted(context, query);
    }

    public record ChatRequest(@NotBlank String query, String category) {}
    public record ChatResponse(String answer, List<String> sources) {}
}
