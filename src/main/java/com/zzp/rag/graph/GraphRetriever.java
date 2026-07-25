package com.zzp.rag.graph;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 图检索器 — 在混合检索+Rerank之后执行，补充知识图谱上下文。
 * <p>
 * 流程：
 * 1. 从用户 query 中提取实体名称
 * 2. Neo4j 中做 N 跳图遍历，找到关联实体和关系路径
 * 3. 格式化为 LLM 可读的文本（实体关系列表）
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GraphRetriever {

    private final EntityExtractor entityExtractor;
    private final GraphStore graphStore;

    @Value("${rag.graph.max-hops:2}")
    private int maxHops;

    /**
     * 从用户查询出发做图遍历，返回格式化的图上下文。
     * @param query 用户原始问题
     * @return 格式化的实体关系文本；失败或无结果返回 ""
     */
    public String expand(String query) {
        // 1. 从查询提取实体名称
        List<String> entityNames = entityExtractor.extractEntityNames(query);
        if (entityNames.isEmpty()) {
            log.debug("[图检索] 未提取到实体 query={}", query.substring(0, Math.min(30, query.length())));
            return "";
        }

        // 2. Neo4j 图遍历
        var result = graphStore.traverse(entityNames, maxHops);
        if (result.isEmpty()) {
            log.debug("[图检索] 实体={} 未找到关联", entityNames);
            return "";
        }

        // 3. 格式化
        String context = result.format();
        log.info("[图检索] 实体={} 路径={}条", entityNames.size(), result.paths().size());
        return context;
    }
}
