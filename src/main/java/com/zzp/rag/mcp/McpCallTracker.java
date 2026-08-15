package com.zzp.rag.mcp;

/**
 * MCP 工具调用标记（ThreadLocal）：
 * 工具在 AiServices 调用线程执行，executor 标记；编排器同线程消费。
 * 流式路径的工具执行与 onToolExecuted 回调同线程，编排器在回调里快照，
 * onCompleteResponse（可能异线程）用快照。
 * <p>
 * 用途：答案事实校验（verify）只针对知识库接地；MCP 工具返回的是权威业务数据，
 * 标记"本轮用过 MCP 工具"即跳过 KB 校验（避免把 MCP 数据误判为无依据删掉）。
 */
public final class McpCallTracker {

    private static final ThreadLocal<Boolean> CALLED = new ThreadLocal<>();
    private static final ThreadLocal<StringBuilder> TOOL_RESULTS = new ThreadLocal<>();
    private static final ThreadLocal<java.util.Map<String, Object>> CHART_DATA = new ThreadLocal<>();

    private McpCallTracker() {}

    public static void markCalled() {
        CALLED.set(true);
    }

    /** 追加一次 MCP 工具返回文本（并入校验依据：MCP 数据可核，防夹带编造） */
    public static void appendResult(String text) {
        if (text == null || text.isBlank()) {
            return;
        }
        StringBuilder sb = TOOL_RESULTS.get();
        if (sb == null) {
            sb = new StringBuilder();
            TOOL_RESULTS.set(sb);
        }
        sb.append(text).append('\n');
    }

    /** 结构化图表数据（数据类工具置入；编排器统一消费到 McpChartSink 供渠道端渲染） */
    public static void putChartData(java.util.Map<String, Object> chart) {
        if (chart != null && !chart.isEmpty()) {
            CHART_DATA.set(chart);
        }
    }

    public static java.util.Map<String, Object> consumeChartData() {
        java.util.Map<String, Object> v = CHART_DATA.get();
        CHART_DATA.remove();
        return v;
    }

    public static boolean consumeCalled() {
        Boolean v = CALLED.get();
        CALLED.remove();
        return Boolean.TRUE.equals(v);
    }

    /** 取走本轮全部工具结果文本（消费后清空） */
    public static String consumeResults() {
        StringBuilder sb = TOOL_RESULTS.get();
        TOOL_RESULTS.remove();
        return sb != null ? sb.toString() : "";
    }

    public static void clear() {
        CALLED.remove();
        TOOL_RESULTS.remove();
        CHART_DATA.remove();
    }
}
