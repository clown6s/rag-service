package com.zzp.rag.skill.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mcp.bridge.McpSchemaConverter;
import com.zzp.rag.mcp.bridge.McpToolExecutor;
import com.zzp.rag.mcp.server.McpServerHandle;
import com.zzp.rag.mcp.server.McpServerRegistry;
import com.zzp.rag.skill.service.BuiltinSkillFallback;
import com.zzp.rag.skill.service.SkillService;
import com.zzp.rag.skill.service.SkillSnapshotCache;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import dev.langchain4j.service.tool.ToolProvider;
import dev.langchain4j.service.tool.ToolProviderRequest;
import dev.langchain4j.service.tool.ToolProviderResult;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 技能注册中心 → LC4j 工具动态装配（阶段 3 核心）。
 * <p>
 * 每次工具循环开始前调用 provideTools：
 * 1. 快照（默认员工 + 已授权技能，TTL 5s + 管理端写操作显式失效）
 * 2. builtin 技能 → DefaultToolExecutor 反射执行存量 @Tool 方法（ThreadLocal 状态机制保留）
 * 3. mcp 技能 → McpServerHandle 连接（冷却重试）+ 工具规格桥接
 * <p>
 * 软依赖：MySQL 不可用或快照加载失败 → 兜底装配两个内置技能（与阶段 2 行为等价）；
 * MCP 连接失败 → 工具从 LLM 视野消失，主链路照常。
 */
@Slf4j
@Component
public class SkillRegistryToolProvider implements ToolProvider {

    private final SkillService skillService;
    private final SkillSnapshotCache snapshotCache;
    private final BuiltinSkillFallback builtinFallback;
    private final McpServerRegistry mcpRegistry;
    private final ObjectMapper objectMapper;
    private final com.zzp.rag.agent.AgentTools agentTools;

    public SkillRegistryToolProvider(SkillService skillService, SkillSnapshotCache snapshotCache,
                                     BuiltinSkillFallback builtinFallback, McpServerRegistry mcpRegistry,
                                     ObjectMapper objectMapper, com.zzp.rag.agent.AgentTools agentTools) {
        this.skillService = skillService;
        this.snapshotCache = snapshotCache;
        this.builtinFallback = builtinFallback;
        this.mcpRegistry = mcpRegistry;
        this.objectMapper = objectMapper;
        this.agentTools = agentTools;
    }

    /** 默认员工是否有启用的 MCP 技能（编排器守卫用：决定预检索空时是兜底还是放行 Agent 工具循环） */
    public boolean mcpSkillsEnabled() {
        SkillSnapshotCache.Snapshot snapshot = snapshotCache.getOrLoad(this::loadSnapshot);
        if (snapshot == null || snapshot.defaultEmployeeId() <= 0) {
            return false;
        }
        return snapshot.skills().stream()
                .anyMatch(s -> s.status() == 1 && "mcp".equals(s.toolType()));
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        // 内置技能（知识库检索/图谱查询）是平台底座，永远装配——不依赖 MySQL 与授权，
        // 与阶段 2 的 .tools(agentTools) 行为等价（软依赖：MySQL 挂掉问答能力不缩水）
        Map<ToolSpecification, ToolExecutor> tools =
                new LinkedHashMap<>(builtinFallback.allTools(agentTools));
        // 扩展技能（MCP）按默认员工授权装配
        SkillSnapshotCache.Snapshot snapshot = snapshotCache.getOrLoad(this::loadSnapshot);
        if (snapshot == null || snapshot.defaultEmployeeId() <= 0) {
            return new ToolProviderResult(tools);   // MySQL 不可用/无员工：只有内置底座
        }
        for (SkillSnapshotCache.AuthorizedSkill skill : snapshot.skills()) {
            if (skill.status() == 1 && "mcp".equals(skill.toolType())) {
                assembleMcpTools(skill, tools);
            }
        }
        return new ToolProviderResult(tools);
    }

    private void assembleMcpTools(SkillSnapshotCache.AuthorizedSkill skill,
                                  Map<ToolSpecification, ToolExecutor> tools) {
        McpServerHandle handle = mcpRegistry.getOrCreate(skill.code(), skill.mcpConfig());
        if (handle == null || !handle.ensureConnected()) {
            return;   // 连接失败/冷却期：工具不进 LLM 视野，主链路照常
        }
        for (McpSchema.Tool mcpTool : handle.tools(skill.toolNames())) {
            ToolSpecification spec = ToolSpecification.builder()
                    .name(mcpTool.name())
                    .description(mcpTool.description())
                    .parameters(McpSchemaConverter.convert(mcpTool.inputSchema()))
                    .build();
            if (tools.containsKey(spec)) {
                log.warn("[技能] 工具名冲突，MCP 工具覆盖: {}（技能 {})", mcpTool.name(), skill.code());
            }
            tools.put(spec, new McpToolExecutor(handle, mcpTool.name(), objectMapper));
        }
    }

    /** 快照加载：默认员工 + 其授权技能（MySQL 不可用返回 null → 兜底路径） */
    private SkillSnapshotCache.Snapshot loadSnapshot() {
        long employeeId = skillService.resolveDefaultEmployeeId();
        if (employeeId <= 0) {
            return new SkillSnapshotCache.Snapshot(-1, java.util.List.of());
        }
        java.util.List<SkillService.SkillView> authorized = skillService.getEmployeeSkills(employeeId);
        if (authorized == null) {
            return null;
        }
        java.util.List<SkillSnapshotCache.AuthorizedSkill> skills = authorized.stream()
                .map(v -> new SkillSnapshotCache.AuthorizedSkill(
                        v.id(), v.code(), v.name(), v.toolType(),
                        v.mcpConfig(), v.toolNames(), v.status()))
                .toList();
        return new SkillSnapshotCache.Snapshot(employeeId, skills);
    }
}
