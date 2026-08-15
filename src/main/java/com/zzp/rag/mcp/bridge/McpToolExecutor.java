package com.zzp.rag.mcp.bridge;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mcp.server.McpServerHandle;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.service.tool.ToolExecutor;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * MCP 工具执行桥：LC4j ToolExecutor → MCP callTool。
 * 软依赖铁律在工具层的落点：任何失败都返回错误文本回喂 LLM（LLM 如实转达），
 * 绝不抛异常打断工具循环；同时置句柄 unhealthy，下一轮装配即从 LLM 视野消失。
 */
@Slf4j
public class McpToolExecutor implements ToolExecutor {

    private final McpServerHandle handle;
    private final String toolName;
    private final ObjectMapper objectMapper;

    public McpToolExecutor(McpServerHandle handle, String toolName, ObjectMapper objectMapper) {
        this.handle = handle;
        this.toolName = toolName;
        this.objectMapper = objectMapper;
    }

    @Override
    public String execute(ToolExecutionRequest request, Object memoryId) {
        com.zzp.rag.mcp.McpCallTracker.markCalled();   // 标记本轮用过 MCP 工具（校验层据此放行）
        try {
            if (!handle.ensureConnected()) {
                return "工具服务不可达（MCP 连接失败），请告知用户当前无法查询";
            }
            Map<String, Object> args = objectMapper.readValue(
                    request.arguments(), new TypeReference<Map<String, Object>>() {});
            McpSchema.CallToolResult result = handle.callTool(toolName, args);
            StringBuilder sb = new StringBuilder();
            if (result.content() != null) {
                for (Object content : result.content()) {
                    if (content instanceof McpSchema.TextContent text) {
                        sb.append(text.text()).append('\n');
                    }
                }
            }
            String text = sb.toString().strip();
            if (Boolean.TRUE.equals(result.isError())) {
                return "工具执行失败: " + text;
            }
            // 图表数据标记：__CHART__{json} 首行 → 解析到 ThreadLocal（本线程=Agent 线程），
            // 从正文剥离（图表 JSON 不喂 LLM/校验层）
            if (text.startsWith("__CHART__")) {
                int nl = text.indexOf('\n');
                if (nl > 0) {
                    try {
                        com.zzp.rag.mcp.McpCallTracker.putChartData(objectMapper.readValue(
                                text.substring("__CHART__".length(), nl),
                                new TypeReference<Map<String, Object>>() {}));
                    } catch (Exception ignored) {
                        // 图表数据损坏只影响展示，不影响数据正文
                    }
                    text = text.substring(nl + 1).strip();
                }
            }
            // 工具结果并入校验依据（防模型在工具数据外夹带编造，如"研发部共42人"）
            com.zzp.rag.mcp.McpCallTracker.appendResult(text);
            return text.isEmpty() ? "（工具无返回内容）" : text;
        } catch (Exception e) {
            handle.markUnhealthy(e.getMessage());
            log.warn("[MCP] 工具调用失败 tool={}: {}", toolName, e.getMessage());
            return "工具调用失败（服务不可达）: " + e.getMessage();
        }
    }
}
