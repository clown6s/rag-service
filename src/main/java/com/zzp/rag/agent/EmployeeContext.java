package com.zzp.rag.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.service.DigitalEmployeeService;
import com.zzp.rag.skill.service.SkillService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数字员工运行时上下文（阶段 4 核心）— 把管理后台配置的"数字员工"绑到对话运行时。
 * <p>
 * 三个职责：
 * 1. 解析请求 agentId → 员工运行时快照（persona 人设 + knowledgeScope 检索过滤表达式），
 *    5 秒 TTL 缓存（管理端改配置最多 5 秒生效，软依赖：MySQL 不可用返回 null = 无绑定）
 * 2. byConversation：conversationId → 快照映射。编排器在对话开始 bind、结束 unbind；
 *    ToolProvider 按 chatMemoryId 反查员工授权（流式链路工具执行与编排器不同线程，
 *    ThreadLocal 传不过去，必须走显式映射）
 * 3. toolScope ThreadLocal：工具线程上的检索过滤表达式。包装执行器在 delegate 前注入、
 *    AgentTools.searchKnowledgeBase 消费（AgentTools 只拿到查询词，拿不到 conversationId）
 */
@Slf4j
@Component
public class EmployeeContext {

    /** 员工运行时快照：persona 人设文本 + scopeExpr 检索过滤表达式（Milvus 布尔表达式） */
    public record EmployeeRuntime(long employeeId, String name, String persona, String scopeExpr) {
        public boolean hasPersona() {
            return persona != null && !persona.isBlank();
        }

        public boolean hasScope() {
            return scopeExpr != null && !scopeExpr.isBlank();
        }
    }

    private static final long TTL_MS = 5_000;
    private static final int MAX_BINDINGS = 500;

    private final SkillService skillService;
    private final DigitalEmployeeService employeeService;
    private final ObjectMapper objectMapper;

    /** conversationId → 员工快照（流式链路跨线程反查） */
    private final ConcurrentHashMap<String, EmployeeRuntime> byConversation = new ConcurrentHashMap<>();

    /** 员工配置解析缓存（employeeId → 快照 + 加载时间；快照可为 null = 员工不存在/停用） */
    private record ResolveEntry(EmployeeRuntime runtime, long loadedAt) {}
    private final ConcurrentHashMap<Long, ResolveEntry> resolveCache = new ConcurrentHashMap<>();

    /** 工具线程的检索过滤表达式（包装执行器注入 → AgentTools 消费） */
    private final ThreadLocal<String> toolScope = new ThreadLocal<>();

    public EmployeeContext(SkillService skillService, DigitalEmployeeService employeeService,
                           ObjectMapper objectMapper) {
        this.skillService = skillService;
        this.employeeService = employeeService;
        this.objectMapper = objectMapper;
    }

    // ==================== 绑定（编排器调用） ====================

    public void bind(String conversationId, EmployeeRuntime runtime) {
        if (conversationId == null || runtime == null) {
            return;
        }
        byConversation.put(conversationId, runtime);
        if (byConversation.size() > MAX_BINDINGS) {
            // 客户端异常断开可能遗留绑定，超限时整体清空（对话重建绑定成本极低）
            byConversation.clear();
            log.warn("[员工上下文] 绑定数超限，已清空");
        }
    }

    public EmployeeRuntime get(String conversationId) {
        return conversationId != null ? byConversation.get(conversationId) : null;
    }

    public void unbind(String conversationId) {
        if (conversationId != null) {
            byConversation.remove(conversationId);
        }
    }

    // ==================== 工具线程范围（包装执行器 → AgentTools） ====================

    public void setToolScope(String scopeExpr) {
        if (scopeExpr != null && !scopeExpr.isBlank()) {
            toolScope.set(scopeExpr);
        }
    }

    /** 取出并清除工具线程的过滤表达式（一次工具调用只消费一次） */
    public String consumeToolScope() {
        String expr = toolScope.get();
        toolScope.remove();
        return expr;
    }

    // ==================== 员工解析（编排器调用） ====================

    /**
     * 解析数字员工运行时快照：
     * 显式 agentId 优先 → 否则默认员工（SkillService 配置项/最小启用员工）→ 无则 null。
     * 员工不存在或停用返回 null（= 无绑定：默认技能 + 全量文档，行为与阶段 3 一致）。
     * 软依赖：MySQL 不可用返回 null，不阻断对话。
     */
    public EmployeeRuntime resolve(Long requestedAgentId) {
        long id = requestedAgentId != null && requestedAgentId > 0
                ? requestedAgentId : skillService.resolveDefaultEmployeeId();
        if (id <= 0) {
            return null;
        }
        long now = System.currentTimeMillis();
        ResolveEntry cached = resolveCache.get(id);
        if (cached != null && now - cached.loadedAt < TTL_MS) {
            return cached.runtime;
        }
        try {
            DigitalEmployeeService.EmployeeView v = employeeService.get(id);
            EmployeeRuntime runtime = (v != null && v.status() == 1)
                    ? new EmployeeRuntime(v.id(), v.name(), v.systemPrompt(), buildScopeExpr(v.knowledgeScope()))
                    : null;
            resolveCache.put(id, new ResolveEntry(runtime, now));
            if (runtime != null) {
                log.info("[员工上下文] 绑定员工 id={} name={} persona={} scope={}",
                        id, v.name(), runtime.hasPersona(), runtime.hasScope());
            } else {
                log.info("[员工上下文] 员工 id={} 不存在或停用，按无绑定运行", id);
            }
            return runtime;
        } catch (Exception e) {
            log.warn("[员工上下文] 员工解析失败（软依赖，按无绑定运行）: {}", e.getMessage());
            return null;
        }
    }

    /** knowledgeScope → Milvus 过滤表达式：'all'/空 → null；JSON 数组 → document_id in [...] */
    private String buildScopeExpr(String knowledgeScope) {
        if (knowledgeScope == null || knowledgeScope.isBlank()
                || "all".equals(knowledgeScope.strip())) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(knowledgeScope);
            if (!node.isArray() || node.isEmpty()) {
                return null;
            }
            List<String> ids = new java.util.ArrayList<>();
            node.forEach(item -> {
                if (item.isTextual()) {
                    ids.add("\"" + item.asText() + "\"");
                }
            });
            if (ids.isEmpty()) {
                return null;
            }
            return "document_id in [" + String.join(", ", ids) + "]";
        } catch (Exception e) {
            // CRUD 层已校验格式，此处异常只能是脏数据——软依赖不阻断
            log.warn("[员工上下文] knowledgeScope 解析失败，按全量运行: {}", e.getMessage());
            return null;
        }
    }
}
