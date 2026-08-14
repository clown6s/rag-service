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

    private McpCallTracker() {}

    public static void markCalled() {
        CALLED.set(true);
    }

    public static boolean consumeCalled() {
        Boolean v = CALLED.get();
        CALLED.remove();
        return Boolean.TRUE.equals(v);
    }

    public static void clear() {
        CALLED.remove();
    }
}
