package com.zzp.rag.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * MCP 服务运行时句柄：连接 + 工具缓存 + 健康状态 + 重试冷却。
 * <p>
 * 软依赖核心：连接失败不抛异常，置 unhealthy 并进入 cooldown；
 * cooldown 到期后下次 ensureConnected 自动重试（服务恢复即自愈）。
 */
@Slf4j
public class McpServerHandle {

    private final String code;
    private final String baseUrl;
    private final String sseEndpoint;
    private final Duration requestTimeout;
    private final Duration initializationTimeout;
    private final long cooldownMs;
    private final ObjectMapper objectMapper;

    private volatile McpSyncClient client;
    private volatile Map<String, McpSchema.Tool> tools = Map.of();
    private final AtomicBoolean healthy = new AtomicBoolean(false);
    private volatile long lastAttemptAt = 0;

    public McpServerHandle(String code, String mcpConfigJson, ObjectMapper objectMapper) {
        this.code = code;
        this.objectMapper = objectMapper;
        String url = "http://127.0.0.1:8080";
        String sse = "/mcp/sse";
        long reqTimeout = 5;
        long initTimeout = 3;
        long cooldown = 30;
        try {
            JsonNode node = objectMapper.readTree(mcpConfigJson);
            if (node.has("baseUrl")) {
                url = node.get("baseUrl").asText();
            }
            if (node.has("sseEndpoint")) {
                sse = node.get("sseEndpoint").asText();
            }
            if (node.has("requestTimeoutSeconds")) {
                reqTimeout = node.get("requestTimeoutSeconds").asLong();
            }
            if (node.has("initializationTimeoutSeconds")) {
                initTimeout = node.get("initializationTimeoutSeconds").asLong();
            }
            if (node.has("retryCooldownSeconds")) {
                cooldown = node.get("retryCooldownSeconds").asLong();
            }
        } catch (Exception e) {
            log.warn("[MCP] 连接配置解析失败 code={}，使用默认值: {}", code, e.getMessage());
        }
        this.baseUrl = url;
        this.sseEndpoint = sse;
        this.requestTimeout = Duration.ofSeconds(reqTimeout);
        this.initializationTimeout = Duration.ofSeconds(initTimeout);
        this.cooldownMs = cooldown * 1000;
    }

    public String code() {
        return code;
    }

    public boolean isHealthy() {
        return healthy.get();
    }

    /** 连接或复用：healthy 直接返回；冷却期内不重试；成功拉取工具列表 */
    public synchronized boolean ensureConnected() {
        if (healthy.get() && client != null) {
            return true;
        }
        long now = System.currentTimeMillis();
        if (now - lastAttemptAt < cooldownMs) {
            return false;
        }
        lastAttemptAt = now;
        try {
            HttpClientSseClientTransport transport =
                    new HttpClientSseClientTransport.Builder(baseUrl)
                            .sseEndpoint(sseEndpoint)
                            .build();
            McpSyncClient newClient = McpClient.sync(transport)
                    .requestTimeout(requestTimeout)
                    .initializationTimeout(initializationTimeout)
                    .clientInfo(new McpSchema.Implementation("rag-service", "1.0.0"))
                    .build();
            newClient.initialize();
            Map<String, McpSchema.Tool> loaded = new LinkedHashMap<>();
            for (McpSchema.Tool t : newClient.listTools().tools()) {
                loaded.put(t.name(), t);
            }
            this.client = newClient;
            this.tools = Map.copyOf(loaded);
            healthy.set(true);
            log.info("[MCP] 已连接 code={} baseUrl={} 工具={}个", code, baseUrl, loaded.size());
            return true;
        } catch (Exception e) {
            healthy.set(false);
            log.warn("[MCP] 连接失败 code={} baseUrl={}（冷却 {}s 后重试）: {}",
                    code, baseUrl, cooldownMs / 1000, e.getMessage());
            return false;
        }
    }

    /** 按白名单过滤工具（null/空 = 全部） */
    public List<McpSchema.Tool> tools(String toolNamesWhitelist) {
        if (toolNamesWhitelist == null || toolNamesWhitelist.isBlank()) {
            return List.copyOf(tools.values());
        }
        List<String> names = List.of(toolNamesWhitelist.split(","));
        return tools.values().stream().filter(t -> names.contains(t.name())).toList();
    }

    public McpSchema.CallToolResult callTool(String toolName, Map<String, Object> args) {
        if (!healthy.get() || client == null) {
            throw new IllegalStateException("MCP 服务不可用（未连接）");
        }
        return client.callTool(new McpSchema.CallToolRequest(toolName, args));
    }

    public void markUnhealthy(String reason) {
        healthy.set(false);
        lastAttemptAt = System.currentTimeMillis();
        log.warn("[MCP] 工具执行失败置不可用 code={}: {}", code, reason);
    }

    public void close() {
        try {
            if (client != null) {
                client.closeGracefully();
            }
        } catch (Exception ignored) {
            // 关闭失败无碍
        }
        healthy.set(false);
    }
}
