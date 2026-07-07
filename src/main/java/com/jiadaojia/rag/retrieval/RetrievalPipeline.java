package com.jiadaojia.rag.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 检索流水线 — 串联 Query改写 → 混合检索 → Rerank 全流程
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RetrievalPipeline {

    private final QueryRewriter queryRewriter;
    private final HybridRetriever hybridRetriever;
    private final Reranker reranker;

    @Value("${rag.retrieval.top-k:20}")
    private int topK;

    /**
     * 执行完整检索链路
     * @param query          用户原始问题
     * @param metadataFilter 业务过滤条件
     */
    public PipelineResult execute(String query, Filter metadataFilter) {
        long start = System.currentTimeMillis();

        // Step 1: Query 改写（Multi-Query + 关键词提取）
        var rewritten = queryRewriter.rewrite(query);
        String searchQuery = rewritten.queries().isEmpty()
                ? query : String.join(" ", rewritten.queries());
        log.info("[检索] 改写: {} → {}", query, searchQuery);

        // Step 2: 混合检索（向量 + BM25）
        List<TextSegment> candidates = hybridRetriever.search(
                searchQuery, rewritten.keywords(), topK, metadataFilter);
        log.info("[检索] 召回: {} 条", candidates.size());

        // Step 3: Cross-Encoder 重排
        List<TextSegment> reranked = reranker.rerank(query, candidates);
        long elapsed = System.currentTimeMillis() - start;
        log.info("[检索] 完成: {} 条, 耗时 {}ms", reranked.size(), elapsed);

        return new PipelineResult(reranked, rewritten);
    }

    /**
     * 将检索结果拼接为 Prompt 上下文
     * @param maxTokens 最大 token 数（粗略按字数估算）
     */
    public String buildContext(PipelineResult result, int maxTokens) {
        if (result.segments().isEmpty()) {
            return "暂无相关资料。";
        }

        StringBuilder sb = new StringBuilder();
        int totalLen = 0;
        int maxChars = maxTokens * 2; // 粗略: 1 token ≈ 2 中文字符

        for (int i = 0; i < result.segments().size(); i++) {
            TextSegment seg = result.segments().get(i);
            String source = seg.metadata().getString("source");
            String text = seg.text();

            String entry = String.format("[来源%d: %s]\n%s\n\n", i + 1,
                    source != null ? source : "未知", text);

            if (totalLen + entry.length() > maxChars) break;
            sb.append(entry);
            totalLen += entry.length();
        }

        return sb.toString();
    }

    public record PipelineResult(
            List<TextSegment> segments,
            QueryRewriter.RewriteResult rewriteInfo) {

        public List<String> getSources() {
            // 去重文件名列表（无编号），来源文件在回答下方单独展示
            return segments.stream()
                    .map(s -> s.metadata().getString("source"))
                    .filter(Objects::nonNull)
                    .distinct()
                    .collect(Collectors.toList());
        }
    }
}
