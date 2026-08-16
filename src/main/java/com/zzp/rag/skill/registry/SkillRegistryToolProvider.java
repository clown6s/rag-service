package com.zzp.rag.skill.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.agent.EmployeeContext;
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
 * 技能注册中心 → LC4j 工具动态装配（阶段 3 核心，阶段 4 按请求员工装配）。
 * <p>
 * 每次工具循环开始前调用 provideTools：
 * 1. 请求员工解析：按 chatMemoryId 反查 EmployeeContext（编排器已绑定）
 *    → 该员工的授权快照（TTL 5s + 管理端写操作显式失效）；未绑定/无员工 → 默认员工
 * 2. builtin 技能 → DefaultToolExecutor 反射执行存量 @Tool 方法，
 *    外包一层范围注入：员工有 knowledgeScope 时，检索工具执行前注入过滤表达式
 * 3. mcp 技能 → McpServerHandle 连接（冷却重试）+ 工具规格桥接
 * <p>
 * 软依赖：MySQL 不可用或快照加载失败 → 兜底装配内置技能（与阶段 2 行为等价）；
 * MCP 连接失败 → 工具从 LLM 视野消失，主链路照常。
 */
@Slf4j
@Component
public class SkillRegistryToolProvider implements ToolProvider {

    /** 流程/制度/规范问法：数据工具从 LLM 视野移除（实况："报销流程是什么"模型调了
     *  oa_employee_expenses 用报销数据作答而非流程制度；与 AgentOrchestrator#DATA_PROCESS_WORDS 同源） */
    private static final java.util.regex.Pattern DATA_PROCESS_WORDS =
            java.util.regex.Pattern.compile("流程|制度|规定|标准|政策|怎么|如何|条件|材料|发票|格式|模板|须知|方法|注意事项");

    private final SkillService skillService;
    private final SkillSnapshotCache snapshotCache;
    private final BuiltinSkillFallback builtinFallback;
    private final McpServerRegistry mcpRegistry;
    private final ObjectMapper objectMapper;
    private final com.zzp.rag.agent.AgentTools agentTools;
    private final EmployeeContext employeeContext;

    public SkillRegistryToolProvider(SkillService skillService, SkillSnapshotCache snapshotCache,
                                     BuiltinSkillFallback builtinFallback, McpServerRegistry mcpRegistry,
                                     ObjectMapper objectMapper, com.zzp.rag.agent.AgentTools agentTools,
                                     EmployeeContext employeeContext) {
        this.skillService = skillService;
        this.snapshotCache = snapshotCache;
        this.builtinFallback = builtinFallback;
        this.mcpRegistry = mcpRegistry;
        this.objectMapper = objectMapper;
        this.agentTools = agentTools;
        this.employeeContext = employeeContext;
    }

    /** 指定员工（-1 = 默认员工）是否有启用的 MCP 技能（编排器守卫用：决定预检索空时是兜底还是放行 Agent 工具循环） */
    public boolean mcpSkillsEnabled(long employeeId) {
        long id = employeeId > 0 ? employeeId : skillService.resolveDefaultEmployeeId();
        if (id <= 0) {
            return false;
        }
        SkillSnapshotCache.Snapshot snapshot = snapshotCache.getOrLoad(id, () -> loadSnapshot(id));
        return snapshot != null && snapshot.skills().stream()
                .anyMatch(s -> s.status() == 1 && "mcp".equals(s.toolType()));
    }

    @Override
    public ToolProviderResult provideTools(ToolProviderRequest request) {
        Object memoryId = request.chatMemoryId();
        // 本轮问题文本：流程/制度问法下剔除数据类 MCP 工具
        // （实况："报销流程是什么"模型调了 oa_employee_expenses 答了报销数据）
        String query = request.userMessage() != null ? request.userMessage().singleText() : "";
        boolean processQuestion = !query.isBlank() && DATA_PROCESS_WORDS.matcher(query).find();
        // 内置技能（知识库检索/图谱查询）是平台底座，永远装配——不依赖 MySQL 与授权，
        // 与阶段 2 的 .tools(agentTools) 行为等价（软依赖：MySQL 挂掉问答能力不缩水）。
        // 阶段 4：员工有 knowledgeScope 时——
        //   a. 检索工具外包范围注入（工具线程上注入过滤表达式，AgentTools 消费后自动清理）
        //   b. queryEntityGraph 直接不提供：图谱是全局实体关系（不按文档归属），
        //      范围过滤罩不住，提供即漏出授权范围外内容
        EmployeeContext.EmployeeRuntime runtime = memoryId != null
                ? employeeContext.get(String.valueOf(memoryId)) : null;
        boolean graphAllowed = runtime == null || !runtime.hasScope();
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        for (Map.Entry<ToolSpecification, ToolExecutor> e
                : builtinFallback.allTools(agentTools).entrySet()) {
            if (!graphAllowed && "queryEntityGraph".equals(e.getKey().name())) {
                continue;
            }
            tools.put(e.getKey(), scopeAware(e.getValue(), memoryId));
        }
        // 扩展技能（MCP）按请求员工授权装配
        long employeeId = resolveEmployeeId(memoryId);
        if (employeeId <= 0) {
            return new ToolProviderResult(tools);   // MySQL 不可用/无员工：只有内置底座
        }
        SkillSnapshotCache.Snapshot snapshot =
                snapshotCache.getOrLoad(employeeId, () -> loadSnapshot(employeeId));
        if (snapshot == null) {
            return new ToolProviderResult(tools);
        }
        for (SkillSnapshotCache.AuthorizedSkill skill : snapshot.skills()) {
            // 流程/制度问法只答知识库：MCP 数据工具不进 LLM 视野
            // （实况：模型搜库无果后调 oa_dept_tree/oa_employee_list 空转打满工具轮次报错）
            if (skill.status() == 1 && "mcp".equals(skill.toolType()) && !processQuestion) {
                assembleMcpTools(skill, tools);
            }
        }
        return new ToolProviderResult(tools);
    }

    /** 请求员工：chatMemoryId 反查员工上下文（编排器绑定）→ 未绑定走默认员工 */
    private long resolveEmployeeId(Object memoryId) {
        if (memoryId != null) {
            EmployeeContext.EmployeeRuntime runtime = employeeContext.get(String.valueOf(memoryId));
            if (runtime != null) {
                return runtime.employeeId();
            }
        }
        return skillService.resolveDefaultEmployeeId();
    }

    /**
     * 检索范围注入包装：员工有 knowledgeScope 时，delegate 执行前把过滤表达式写入
     * EmployeeContext 工具线程位（AgentTools.searchKnowledgeBase 消费并清理）。
     * 工具执行与 provideTools 不同线程（流式链路），scope 只能经此闭包传过去。
     */
    private ToolExecutor scopeAware(ToolExecutor delegate, Object memoryId) {
        if (memoryId == null) {
            return delegate;
        }
        return (request, memId) -> {
            EmployeeContext.EmployeeRuntime runtime = employeeContext.get(String.valueOf(memoryId));
            if (runtime != null && runtime.hasScope()) {
                employeeContext.setToolScope(runtime.scopeExpr());
            }
            try {
                return delegate.execute(request, memId);
            } finally {
                employeeContext.consumeToolScope();
            }
        };
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

    /** 快照加载：指定员工 + 其授权技能（MySQL 不可用返回 null → 兜底路径） */
    private SkillSnapshotCache.Snapshot loadSnapshot(long employeeId) {
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
