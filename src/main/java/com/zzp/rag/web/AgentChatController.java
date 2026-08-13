package com.zzp.rag.web;

import com.zzp.rag.agent.AgentOrchestrator;
import com.zzp.rag.agent.AgentOrchestrator.AgentStreamHandler;
import com.zzp.rag.agent.LawCitationGuard;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.audit.AuditEvent.AuditContext;
import com.zzp.rag.audit.AuditService;
import jakarta.annotation.PreDestroy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Agent 对话 API（数字员工最小闭环）
 * POST /api/rag/agent/chat          — 非流式
 * POST /api/rag/agent/chat/stream   — SSE 流式（route/tool/token/sources/done 事件）
 * <p>
 * 流程：R1 意图路由 → 闲聊直达 / 工具循环问答；会话记忆由服务端按 conversationId 管理，
 * 前端不再传 history。与 /api/rag/chat 固定流水线并存，逐步灰度切换。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/agent")
public class AgentChatController {

    private final AgentOrchestrator orchestrator;
    private final AuditService auditService;
    private final LawCitationGuard citationGuard;

    private final ExecutorService executor = new ThreadPoolExecutor(
            16, 16, 0L, TimeUnit.MILLISECONDS,
            new LinkedBlockingQueue<>(32),
            new ThreadPoolExecutor.AbortPolicy());

    public AgentChatController(AgentOrchestrator orchestrator, AuditService auditService,
                               LawCitationGuard citationGuard) {
        this.orchestrator = orchestrator;
        this.auditService = auditService;
        this.citationGuard = citationGuard;
    }

    @PreDestroy
    public void shutdown() {
        log.info("[Agent][SSE] 关闭线程池...");
        executor.shutdown();
        try {
            if (!executor.awaitTermination(10, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    // ==================== 非流式 ====================

    @PostMapping("/chat")
    public ApiResponse<AgentChatResponse> chat(@Valid @RequestBody AgentChatRequest req) {
        long start = System.currentTimeMillis();
        String conversationId = resolveConversationId(req.conversationId());
        AuditContext ctx = AuditContext.capture();
        try {
            AgentOrchestrator.AgentAnswer result = orchestrator.answer(conversationId, req.query());
            // 整段格式修复（与固定流水线端点一致的兜底）
            String answer = StreamTokenFixer.fixFull(result.answer());
            // 法名引用校验：库外引用追加标注（防引用幻觉）
            String annotation = citationGuard.validate(answer);
            if (annotation != null) {
                answer = answer + annotation;
            }
            result = new AgentOrchestrator.AgentAnswer(
                    answer, result.sources(), result.route(), result.rerankDegraded());
            auditService.record(buildAuditEvent(req, result, ctx,
                    System.currentTimeMillis() - start, "SUCCESS", null));
            return ApiResponse.ok(new AgentChatResponse(
                    result.answer(), result.sources(), result.route(), conversationId));
        } catch (Exception e) {
            log.error("[Agent] 对话失败 query={}", req.query(), e);
            auditService.record(buildAuditEvent(req, null, ctx,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage()));
            return ApiResponse.fail("处理失败: " + e.getMessage());
        }
    }

    // ==================== SSE 流式 ====================

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody AgentChatRequest req) {
        SseEmitter emitter = new SseEmitter(300_000L);
        String conversationId = resolveConversationId(req.conversationId());

        // 异步线程/回调线程不继承 RequestContext 和 MDC，先捕获审计上下文
        AuditContext auditCtx = AuditContext.capture();
        Map<String, String> mdcCtx = MDC.getCopyOfContextMap();

        executor.execute(() -> {
            long start = System.currentTimeMillis();
            StringBuilder fullAnswer = new StringBuilder();
            // 检索被调用且一无所获时，抑制模型输出，用固定句式兜底（每次工具执行后刷新）
            final boolean[] suppressTokens = {false};
            try {
                orchestrator.answerStream(conversationId, req.query(), new AgentStreamHandler() {
                    @Override
                    public void onRoute(String route) {
                        try {
                            emitter.send(SseEmitter.event().name("route").data(route));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onToken(String token) {
                        if (suppressTokens[0]) {
                            return; // 检索空手而归，丢弃模型输出（不追加也不下发）
                        }
                        // token 级格式修复（与固定流水线端点一致），修复后再下发给前端
                        String fixed = StreamTokenFixer.fix(token, fullAnswer);
                        fullAnswer.append(fixed);
                        try {
                            emitter.send(SseEmitter.event().name("token").data(fixed));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onToolExecuted(String toolName, String arguments, boolean retrievalEmpty) {
                        log.info("[Agent][SSE] 工具执行: {} {} retrievalEmpty={}", toolName, arguments, retrievalEmpty);
                        suppressTokens[0] = retrievalEmpty;
                        try {
                            emitter.send(SseEmitter.event().name("tool")
                                    .data(Map.of("tool", toolName, "arguments", arguments)));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onComplete(String route, List<String> sources, boolean degraded) {
                        try {
                            if (suppressTokens[0]) {
                                // 兜底句式作为唯一 token 下发
                                fullAnswer.append(AgentOrchestrator.NO_ANSWER);
                                emitter.send(SseEmitter.event().name("token").data(AgentOrchestrator.NO_ANSWER));
                            } else {
                                // 法名引用校验：库外引用追加标注（作为末尾 token 事件下发）
                                String annotation = citationGuard.validate(fullAnswer.toString());
                                if (annotation != null) {
                                    fullAnswer.append(annotation);
                                    emitter.send(SseEmitter.event().name("token").data(annotation));
                                }
                            }
                            emitter.send(SseEmitter.event().name("sources").data(sources));
                            emitter.send(SseEmitter.event()
                                    .name("done")
                                    .data("[DONE]"));
                            emitter.complete();
                            auditService.record(buildAuditEvent(req,
                                    new AgentOrchestrator.AgentAnswer(
                                            fullAnswer.toString(), sources, route, degraded),
                                    auditCtx, System.currentTimeMillis() - start, "SUCCESS", null));
                        } catch (IOException e) {
                            emitter.completeWithError(e);
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.error("[Agent][SSE] 生成失败", error);
                        auditService.record(buildAuditEvent(req, null, auditCtx,
                                System.currentTimeMillis() - start,
                                "GENERATION_FAILED", error.getMessage()));
                        try {
                            emitter.send(SseEmitter.event()
                                    .name("error")
                                    .data("生成失败: " + error.getMessage()));
                            emitter.complete();
                        } catch (IOException ignored) {
                            emitter.completeWithError(error);
                        }
                    }
                });
            } catch (Exception e) {
                log.error("[Agent][SSE] 处理失败", e);
                auditService.record(buildAuditEvent(req, null, auditCtx,
                        System.currentTimeMillis() - start, "FAILED", e.getMessage()));
                try {
                    emitter.send(SseEmitter.event()
                            .name("error")
                            .data("处理失败: " + e.getMessage()));
                    emitter.complete();
                } catch (IOException ignored) {
                    emitter.completeWithError(e);
                }
            } finally {
                MDC.clear();
            }
        });

        emitter.onCompletion(() -> log.debug("[Agent][SSE] 客户端断开"));
        emitter.onTimeout(() -> {
            log.debug("[Agent][SSE] 超时");
            emitter.complete();
        });

        return emitter;
    }

    // ==================== 审计 ====================

    private AuditEvent buildAuditEvent(AgentChatRequest req, AgentOrchestrator.AgentAnswer result,
                                       AuditContext ctx, long totalMs, String status, String error) {
        List<String> sources = result != null ? result.sources() : List.of();
        String answer = result != null ? result.answer() : null;
        return new AuditEvent(
                ctx.traceId(),
                LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                ctx.user(), ctx.ip(), "agent/chat",
                null,                       // category 暂未启用
                req.query(),
                0,                          // history 由服务端记忆管理，此处不记录条数
                sources,
                0,                          // retrievalMs 在工具内部，暂未透出
                result != null && result.rerankDegraded(),
                0,
                AuditEvent.truncate(answer, 500),
                answer != null ? answer.length() : 0,
                totalMs,
                status,
                error);
    }

    private String resolveConversationId(String conversationId) {
        return conversationId != null && !conversationId.isBlank()
                ? conversationId
                : UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    // ==================== 请求/响应 record ====================

    public record AgentChatRequest(
            @NotBlank String query,
            String conversationId) {}

    public record AgentChatResponse(
            String answer,
            List<String> sources,
            String route,            // CHAT | KNOWLEDGE_QA（调试/观测用）
            String conversationId) {}
}
