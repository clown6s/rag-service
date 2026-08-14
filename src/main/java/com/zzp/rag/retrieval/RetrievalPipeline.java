package com.zzp.rag.retrieval;

import com.zzp.rag.graph.GraphRetriever;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 检索流水线 — 串联 Query改写 → 混合检索 → Rerank → 图遍历 全流程
 */
@Slf4j
@Component
public class RetrievalPipeline {

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final Reranker reranker;
    private final GraphRetriever graphRetriever;

    @Value("${rag.retrieval.top-k:20}")
    private int topK;

    /** 重排后片段的相关度分数低于该阈值则不进上下文（无关文档混入是检索精度的主要噪声源） */
    @Value("${rag.retrieval.min-rerank-score:0.3}")
    private double minRerankScore;

    public RetrievalPipeline(
            QueryRewriter queryRewriter,
            HybridRetriever hybridRetriever,
            Reranker reranker,
            GraphRetriever graphRetriever) {
        this.queryRewriter = queryRewriter;
        this.hybridRetriever = hybridRetriever;
        this.reranker = reranker;
        this.graphRetriever = graphRetriever;
    }

    /**
     * 执行完整检索链路
     * @param query          用户原始问题
     * @param milvusFilter   Milvus 布尔过滤表达式（数字员工 knowledgeScope；null 不过滤）
     * @param includeGraph   是否做知识图谱遍历。带 knowledgeScope 的员工必须 false——
     *                       图谱是全局实体关系（不按文档归属），范围过滤罩不住它，
     *                       否则授权范围外内容会从图谱路径漏出
     */
    public PipelineResult execute(String query, String milvusFilter, boolean includeGraph) {
        long start = System.currentTimeMillis();

        // Step 1: Query 改写（Multi-Query + 关键词提取）
        var rewritten = queryRewriter.rewrite(query);
        // 短查询(≤4字)用改写结果扩展检索路径，长查询只取关键词补充
        String expandedQuery = (query.length() <= 4 && !rewritten.queries().isEmpty())
                ? String.join(" ", rewritten.queries()) : null;
        log.info("[检索] 改写: {} → expandedQuery={}, keywords={}", query, expandedQuery, rewritten.keywords());

        // Step 2: 混合检索（短查询：原query+expandedQuery双路dense+BM25用expanded；长查询：原query单路）
        List<TextSegment> candidates = hybridRetriever.search(
                query, expandedQuery, rewritten.keywords(), topK, milvusFilter);
        log.info("[检索] 召回: {} 条", candidates.size());

        // Step 3: Cross-Encoder 重排（始终用原 query，防漂移）
        Reranker.RerankResult rerankResult = reranker.rerank(query, candidates);
        List<TextSegment> reranked = rerankResult.segments();
        if (rerankResult.degraded()) {
            log.warn("[检索] rerank 降级，返回原始 Top-{}", reranked.size());
        } else if (rerankResult.topScore() != null) {
            // Step 3.5: 无关片段过滤（小知识库中无关文档也会被召回，rerank 分数是最好的相关度信号）
            List<TextSegment> filtered = reranked.stream()
                    .filter(seg -> {
                        Object score = seg.metadata().toMap().get("rerank_score");
                        return score == null || ((Number) score).doubleValue() >= minRerankScore;
                    })
                    .toList();
            if (filtered.size() < reranked.size()) {
                log.info("[检索] 阈值过滤: {} → {} 条 (min-rerank-score={})",
                        reranked.size(), filtered.size(), minRerankScore);
                reranked = filtered;
            }
        }

        // Step 4: 知识图谱遍历（从用户 query 提取实体，Neo4j 多跳查找关联）
        String graphContext = includeGraph ? graphRetriever.expand(query) : "";

        long elapsed = System.currentTimeMillis() - start;
        log.info("[检索] 完成: {} 条, 图上下文 {}chars, 耗时 {}ms",
                reranked.size(), graphContext.length(), elapsed);

        return new PipelineResult(reranked, rewritten, graphContext, elapsed,
                rerankResult.degraded(), rerankResult.topScore());
    }

    /**
     * 将检索结果拼接为 Prompt 上下文
     * @param maxTokens 最大 token 数（粗略按字数估算）
     */
    public String buildContext(PipelineResult result, int maxTokens) {
        if (result.segments().isEmpty() && result.graphContext().isBlank()) {
            return "暂无相关资料。";
        }

        StringBuilder sb = new StringBuilder();

        // 知识图谱上下文（实体关系）
        if (!result.graphContext().isBlank()) {
            sb.append("## 知识图谱关联\n").append(result.graphContext()).append("\n\n");
        }

        int totalLen = sb.length();
        int maxChars = maxTokens * 2; // 粗略: 1 token ≈ 2 中文字符

        for (int i = 0; i < result.segments().size(); i++) {
            TextSegment seg = result.segments().get(i);
            String source = seg.metadata().getString("source");
            String text = seg.text();

            String entry = String.format("[来源: %s]\n%s\n\n",
                    source != null ? source : "未知", text);

            if (totalLen + entry.length() > maxChars) break;
            sb.append(entry);
            totalLen += entry.length();
        }

        log.info("[检索] context预览(前800字): {}", sb.substring(0, Math.min(800, sb.length())));
        return sb.toString();
    }

    public record PipelineResult(
            List<TextSegment> segments,
            QueryRewriter.RewriteResult rewriteInfo,
            String graphContext,
            long elapsedMs,
            boolean rerankDegraded,
            Double topScore) {

        /** 返回去重文件名列表（用于 parseCitedSources 匹配 LLM 引用的文件名） */
        public List<String> getIndexedSources() {
            return segments.stream()
                    .map(s -> s.metadata().getString("source"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }

        /** 去重文件名列表（fallback，未解析引用时用） */
        public List<String> getSources() {
            return segments.stream()
                    .map(s -> s.metadata().getString("source"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }
    }
}
