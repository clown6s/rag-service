package com.zzp.rag.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * MCP 连接句柄注册表：按技能 code 管理运行时连接。
 * 连接状态每次 provideTools 决策（内存操作微秒级），管理端修改 mcpConfig 后
 * invalidate 丢弃旧句柄（下次按新配置重连）。
 */
@Slf4j
@Component
public class McpServerRegistry {

    private final ObjectMapper objectMapper;
    private final Map<String, McpServerHandle> handles = new ConcurrentHashMap<>();

    public McpServerRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 取或建句柄（配置相同复用，配置变更由 invalidate 先行丢弃） */
    public McpServerHandle getOrCreate(String code, String mcpConfigJson) {
        return handles.computeIfAbsent(code, c -> new McpServerHandle(c, mcpConfigJson, objectMapper));
    }

    /** 管理端修改技能配置/删除技能后调用：丢句柄，下次按新配置重建连接 */
    public void invalidate(String code) {
        McpServerHandle removed = handles.remove(code);
        if (removed != null) {
            removed.close();
            log.info("[MCP] 句柄已失效 code={}", code);
        }
    }

    @PreDestroy
    public void closeAll() {
        handles.values().forEach(McpServerHandle::close);
        handles.clear();
    }
}
