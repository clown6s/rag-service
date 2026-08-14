package com.zzp.rag.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.MDC;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.List;

/**
 * 审计事件 — 一次 RAG 对话的完整记录
 * 合规审计：谁在什么时候问了什么、检索了什么、答了什么、是否降级
 * 序列化为 JSONL 由 {@link AuditService} 异步落盘
 */
public record AuditEvent(
        String traceId,           // 全链路 ID（X-Trace-Id 透传，缺省生成）
        String timestamp,         // ISO-8601
        String user,              // X-User-Id 请求头，未接统一身份前为 anonymous
        String ip,
        String endpoint,          // chat | chat/stream
        String category,          // 业务分类（知识库过滤）
        String query,             // 用户原始问题
        int historyCount,         // 多轮历史条数
        List<String> sources,     // 本次检索命中的来源文件
        long retrievalMs,         // 检索链路耗时（改写+召回+重排+图遍历）
        boolean rerankDegraded,   // rerank 是否发生降级（sidecar/API 故障）
        int graphContextChars,    // 知识图谱上下文长度
        String answerPreview,     // 回答预览（截断 500 字符）
        int answerLength,         // 回答完整长度
        long totalMs,             // 端到端耗时
        String status,            // SUCCESS | FAILED | GENERATION_FAILED | RETRIEVAL_FAILED
        String error,             // 失败原因
        long employeeId) {        // 回答的数字员工 id（0 = 默认员工/未绑定；数字员工治理追溯）

    /** MDC 键：鉴权拦截器写入的认证用户名（AuditContext.capture 优先读取） */
    public static final String MDC_USER_KEY = "rag.auth.user";

    /** 回答过长时截断（控制审计文件体积；归档策略后续按合规要求调整） */
    public static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "...(截断)";
    }

    /**
     * 审计上下文（traceId/用户/IP）— 用于跨线程传递
     * SSE 流式响应的生成线程不继承 RequestContext 和 MDC，必须在线程切换前捕获
     */
    public record AuditContext(String traceId, String user, String ip) {

        public static AuditContext capture() {
            ServletRequestAttributes attrs =
                    (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
            HttpServletRequest req = attrs != null ? attrs.getRequest() : null;
            // 身份优先级：MDC（鉴权拦截器写入，后台登录态）> X-User-Id 头 > anonymous
            String user = MDC.get(MDC_USER_KEY);
            if (user == null || user.isBlank()) {
                user = req != null ? req.getHeader("X-User-Id") : null;
            }
            if (user == null || user.isBlank()) {
                user = "anonymous";
            }
            String traceId = MDC.get(TraceIdFilter.TRACE_ID_KEY);
            return new AuditContext(
                    traceId != null ? traceId : "-",
                    user,
                    req != null ? req.getRemoteAddr() : "-");
        }
    }
}
