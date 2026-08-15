package com.zzp.rag.mcp;

/**
 * MCP 图表数据暂存（编排器 → 渠道端）：
 * 编排器 answer() 结束前把本轮工具产生的结构化图表数据放到这里；
 * 飞书桥在拿到回答后 getAndClear 消费并渲染图表卡片。
 * 其他渠道（Web 流式）不消费：下一次写入自然覆盖，无泄漏。
 */
public final class McpChartSink {

    private static volatile java.util.Map<String, Object> data;

    private McpChartSink() {}

    public static void put(java.util.Map<String, Object> chart) {
        data = chart;
    }

    public static java.util.Map<String, Object> getAndClear() {
        java.util.Map<String, Object> v = data;
        data = null;
        return v;
    }
}
