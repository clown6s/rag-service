package com.jiadaojia.rag.retrieval;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 混合检索器：向量 ANN + BM25 关键词 → RRF 融合
 *
 * Milvus 2.5 原生支持 BM25 全文索引，通过标量过滤实现关键词检索。
 * 双路并行检索后 RRF 融合排序。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HybridRetriever {

    private final MilvusEmbeddingStore embeddingStore;
    private final EmbeddingModel embeddingModel;

    /** RRF 融合参数 k（控制排名权重衰减速度） */
    private static final int RRF_K = 60;

    /**
     * 混合检索入口
     * @param query          原始查询
     * @param keywords       BM25 关键词（由 QueryRewriter 提取）
     * @param topK           最终返回数
     * @param metadataFilter 元数据过滤（如 category=售后）
     */
    public List<TextSegment> search(String query, List<String> keywords,
                                     int topK, Filter metadataFilter) {

        // 路1: 向量检索
        Embedding queryEmbedding = embeddingModel.embed(query).content();
        EmbeddingSearchRequest vectorReq = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(topK * 2)
                .minScore(0.5)
                .filter(metadataFilter)
                .build();
        List<EmbeddingMatch<TextSegment>> vectorResults =
                embeddingStore.search(vectorReq).matches();

        // 路2: BM25 关键词检索
        List<EmbeddingMatch<TextSegment>> bm25Results = Collections.emptyList();
        if (keywords != null && !keywords.isEmpty()) {
            bm25Results = searchByKeywords(keywords, topK * 2, metadataFilter);
        }

        // 融合
        List<TextSegment> fused = rrfFusion(vectorResults, bm25Results, topK);
        log.info("[混合检索] 向量={} BM25={} 融合={}",
                vectorResults.size(),
                bm25Results.size(),
                fused.size());
        return fused;
    }

    /**
     * 关键词检索 — TODO: 通过 Milvus 原生 SDK 走 BM25 全文索引
     * 当前返回空列表，RRF 融合仅使用向量检索结果
     */
    private List<EmbeddingMatch<TextSegment>> searchByKeywords(
            List<String> keywords, int maxResults, Filter metadataFilter) {
        // TODO: 使用 MilvusClientV2 原生 SDK 执行 BM25 全文检索
        // String keywordExpr = keywords.stream()
        //         .map(k -> "(text like '%" + k.replace("'", "''") + "%')")
        //         .collect(Collectors.joining(" or "));
        return Collections.emptyList();
    }

    /**
     * RRF (Reciprocal Rank Fusion) 融合
     * score = Σ 1/(k + rank_i)
     *
     * 优点：不需要分数归一化，对两路检索的分数尺度差异鲁棒
     */
    private List<TextSegment> rrfFusion(
            List<EmbeddingMatch<TextSegment>> vectorResults,
            List<EmbeddingMatch<TextSegment>> bm25Results,
            int topK) {

        Map<String, Double> rrfScores = new LinkedHashMap<>();
        Map<String, TextSegment> segmentMap = new LinkedHashMap<>();

        // 向量路排名贡献
        for (int i = 0; i < vectorResults.size(); i++) {
            TextSegment seg = vectorResults.get(i).embedded();
            String id = seg.metadata().getString("document_id");
            if (id != null) {
                rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
                segmentMap.putIfAbsent(id, seg);
            }
        }

        // BM25 路排名贡献
        for (int i = 0; i < bm25Results.size(); i++) {
            TextSegment seg = bm25Results.get(i).embedded();
            String id = seg.metadata().getString("document_id");
            if (id != null) {
                rrfScores.merge(id, 1.0 / (RRF_K + i + 1), Double::sum);
                segmentMap.putIfAbsent(id, seg);
            }
        }

        // 降序排列
        return rrfScores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> segmentMap.get(e.getKey()))
                .collect(Collectors.toList());
    }
}
