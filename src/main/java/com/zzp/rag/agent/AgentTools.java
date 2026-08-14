package com.zzp.rag.agent;

import com.zzp.rag.graph.GraphRetriever;
import com.zzp.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Agent 工具集 — 把检索能力暴露给 LLM 决策循环
 * <p>
 * 设计：工具只做"文本进、结果出"，LLM 决定要不要调、调几次（对比固定流水线每次无脑跑全链路）。
 * <p>
 * 状态记录用 ThreadLocal 而非 @MemoryId：实测 LangChain4j 1.17 的 @MemoryId 注入到
 * 独立工具类时拿到的不是 AI 服务的 memory id，而是占位串 "current"，无法作为会话 key。
 * 工具执行与编排器调用同线程，ThreadLocal 天然按一次调用隔离，编排器读完后清理。
 */
@Slf4j
@Component
public class AgentTools {

    /** 一次 Agent 调用的工具执行状态（供响应/审计/兜底判断使用，非业务数据） */
    private record SearchRecord(List<String> sources, boolean rerankDegraded,
                                boolean searchCalled, boolean graphFound, Double topScore) {}

    private final RetrievalPipeline retrievalPipeline;
    private final GraphRetriever graphRetriever;
    private final EmployeeContext employeeContext;
    private final ThreadLocal<SearchRecord> localSearch = new ThreadLocal<>();

    /** 最高相关度分数低于该阈值视为"未命中"（bge-reranker sigmoid 分数 0-1） */
    @Value("${rag.agent.min-relevance-score:0.3}")
    private double minRelevanceScore;

    public AgentTools(RetrievalPipeline retrievalPipeline, GraphRetriever graphRetriever,
                      EmployeeContext employeeContext) {
        this.retrievalPipeline = retrievalPipeline;
        this.graphRetriever = graphRetriever;
        this.employeeContext = employeeContext;
    }

    /**
     * 检索知识库（改写→混合检索→重排→图遍历全链路）
     * LLM 判断用户问题需要资料支撑时调用，可多次调用补充信息
     * <p>
     * 阶段 4：员工 knowledgeScope 由 SkillRegistryToolProvider 的包装执行器在工具线程注入
     * （EmployeeContext.toolScope），此处消费一次即清理——范围过滤对 LLM 透明。
     */
    @Tool("检索知识库，返回相关文档片段。用户问知识性/事实性问题时调用，可多次调用补充信息")
    public String searchKnowledgeBase(@P("检索查询词") String query) {
        String scopeExpr = employeeContext.consumeToolScope();
        log.info("[AgentTool] 检索知识库: {}{}", query,
                scopeExpr != null ? "（范围过滤: " + scopeExpr + "）" : "");
        // 范围过滤时不走图谱（图谱不按文档归属，会漏出授权范围外内容）
        var result = retrievalPipeline.execute(query, scopeExpr, scopeExpr == null);
        SearchRecord prev = localSearch.get();
        localSearch.set(new SearchRecord(
                result.getSources(), result.rerankDegraded(), true,
                prev != null && prev.graphFound(), result.topScore()));
        return retrievalPipeline.buildContext(result, 3000);
    }

    /** 查询知识图谱（实体关系、多跳关联） */
    @Tool("查询知识图谱：用户问实体关系类问题（X和Y什么关系、X关联了谁）时调用")
    public String queryEntityGraph(@P("实体名称或关系查询") String query) {
        log.info("[AgentTool] 查询图谱: {}", query);
        String result = graphRetriever.expand(query);
        boolean graphFound = result != null && !result.isBlank();
        SearchRecord prev = localSearch.get();
        localSearch.set(new SearchRecord(
                prev != null ? prev.sources() : List.of(),
                prev != null && prev.rerankDegraded(),
                prev != null && prev.searchCalled(),
                graphFound,
                prev != null ? prev.topScore() : null));
        return result;
    }

    /** 取最近一次检索命中的来源文件（响应返回给前端展示） */
    public List<String> getSources() {
        SearchRecord record = localSearch.get();
        return record != null ? record.sources() : List.of();
    }

    /** 最近一次检索是否发生 rerank 降级（审计用） */
    public boolean isRerankDegraded() {
        SearchRecord record = localSearch.get();
        return record != null && record.rerankDegraded();
    }

    /**
     * 检索被调用过且未命中相关内容：
     * 无文档命中（且图谱无内容），或最高相关度分数低于阈值（召回非空但不相关，小知识库常见）
     * 此时 LLM 输出不可信（会倾向补充自身知识），编排器应使用固定句式兜底
     */
    public boolean isRetrievalEmpty() {
        SearchRecord record = localSearch.get();
        if (record == null || !record.searchCalled() || record.graphFound()) {
            return false;
        }
        if (record.sources().isEmpty()) {
            return true;
        }
        // topScore 为 null 说明 rerank 双通道均失败（无法判定相关性），此时不触发兜底
        return record.topScore() != null && record.topScore() < minRelevanceScore;
    }

    /** 清理本次调用的状态（编排器在一次调用结束后调用） */
    public void clear() {
        localSearch.remove();
    }
}
