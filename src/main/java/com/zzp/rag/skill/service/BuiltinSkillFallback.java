package com.zzp.rag.skill.service;

import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.service.tool.DefaultToolExecutor;
import dev.langchain4j.service.tool.ToolExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 内置技能兜底注册表：MySQL 不可用或暂无默认员工时，ToolProvider 直接装配
 * 这两个存量工具（searchKnowledgeBase / queryEntityGraph），
 * 行为与阶段 2 的 .tools(agentTools) 逐字节等价（同实例反射调用，ThreadLocal 状态照常）。
 * <p>
 * 描述文本必须与 AgentTools 的 @Tool 注解原文一致（LLM 触发行为不漂移）。
 */
@Slf4j
@Component
public class BuiltinSkillFallback {

    public static final String CODE_SEARCH_KB = "builtin_search_kb";
    public static final String CODE_ENTITY_GRAPH = "builtin_entity_graph";

    public record BuiltinToolDef(String code, ToolSpecification spec, Method method) {}

    private final Map<String, BuiltinToolDef> defs = buildDefs();

    private static Map<String, BuiltinToolDef> buildDefs() {
        Map<String, BuiltinToolDef> map = new LinkedHashMap<>();
        map.put(CODE_SEARCH_KB, new BuiltinToolDef(CODE_SEARCH_KB,
                ToolSpecification.builder()
                        .name("searchKnowledgeBase")
                        .description("检索知识库，返回相关文档片段。用户问知识性/事实性问题时调用，可多次调用补充信息")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("query", "检索查询词")
                                .required("query")
                                .build())
                        .build(),
                method("searchKnowledgeBase")));
        map.put(CODE_ENTITY_GRAPH, new BuiltinToolDef(CODE_ENTITY_GRAPH,
                ToolSpecification.builder()
                        .name("queryEntityGraph")
                        .description("查询知识图谱：用户问实体关系类问题（X和Y什么关系、X关联了谁）时调用")
                        .parameters(JsonObjectSchema.builder()
                                .addStringProperty("query", "实体名称或关系查询")
                                .required("query")
                                .build())
                        .build(),
                method("queryEntityGraph")));
        return Map.copyOf(map);
    }

    private static Method method(String name) {
        try {
            return com.zzp.rag.agent.AgentTools.class.getMethod(name, String.class);
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("内置工具方法不存在: " + name, e);
        }
    }

    public BuiltinToolDef find(String code) {
        return defs.get(code);
    }

    /** 兜底装配：全部内置工具（spec → 反射 executor） */
    public Map<ToolSpecification, ToolExecutor> allTools(Object target) {
        Map<ToolSpecification, ToolExecutor> tools = new LinkedHashMap<>();
        defs.values().forEach(d -> tools.put(d.spec(), new DefaultToolExecutor(target, d.method())));
        log.debug("[技能] 兜底装配 {} 个内置工具", tools.size());
        return tools;
    }
}
