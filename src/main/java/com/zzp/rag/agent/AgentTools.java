package com.zzp.rag.agent;

import com.zzp.rag.graph.GraphRetriever;
import com.zzp.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.service.MemoryId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 工具集 — 把检索能力暴露给 LLM 决策循环
 * <p>
 * 设计：工具只做"文本进、结果出"，LLM 决定要不要调、调几次（对比固定流水线每次无脑跑全链路）。
 * 通过 @MemoryId 拿到会话 ID，记录最近一次检索的来源与降级状态，供响应返回前端展示与审计。
 */
@Slf4j
@Component
public class AgentTools {

    /** 单次会话最近一次检索的产物（供响应/审计使用，非业务数据） */
    private record SearchRecord(List<String> sources, boolean rerankDegraded) {}

    private static final int MAX_SESSIONS = 10_000;

    private final RetrievalPipeline retrievalPipeline;
    private final GraphRetriever graphRetriever;
    private final ConcurrentHashMap<String, SearchRecord> lastSearch = new ConcurrentHashMap<>();

    public AgentTools(RetrievalPipeline retrievalPipeline, GraphRetriever graphRetriever) {
        this.retrievalPipeline = retrievalPipeline;
        this.graphRetriever = graphRetriever;
    }

    /**
     * 检索知识库（改写→混合检索→重排→图遍历全链路）
     * LLM 判断用户问题需要资料支撑时调用，可多次调用补充信息
     */
    @Tool("检索知识库，返回相关文档片段。用户问知识性/事实性问题时调用，可多次调用补充信息")
    public String searchKnowledgeBase(@MemoryId String conversationId, @P("检索查询词") String query) {
        log.info("[AgentTool] 检索知识库: {}", query);
        var result = retrievalPipeline.execute(query, null);
        remember(conversationId, new SearchRecord(result.getSources(), result.rerankDegraded()));
        return retrievalPipeline.buildContext(result, 3000);
    }

    /** 查询知识图谱（实体关系、多跳关联） */
    @Tool("查询知识图谱：用户问实体关系类问题（X和Y什么关系、X关联了谁）时调用")
    public String queryEntityGraph(@P("实体名称或关系查询") String query) {
        log.info("[AgentTool] 查询图谱: {}", query);
        return graphRetriever.expand(query);
    }

    /** 取最近一次检索命中的来源文件（响应返回给前端展示） */
    public List<String> getSources(String conversationId) {
        SearchRecord record = lastSearch.get(conversationId);
        return record != null ? record.sources() : List.of();
    }

    /** 最近一次检索是否发生 rerank 降级（审计用） */
    public boolean isRerankDegraded(String conversationId) {
        SearchRecord record = lastSearch.get(conversationId);
        return record != null && record.rerankDegraded();
    }

    private void remember(String conversationId, SearchRecord record) {
        if (lastSearch.size() > MAX_SESSIONS) {
            log.warn("[AgentTool] 会话记录超过 {} 条，清空（仅影响来源展示，不影响会话记忆）", MAX_SESSIONS);
            lastSearch.clear();
        }
        lastSearch.put(conversationId, record);
    }
}
