package com.jiadaojia.rag.retrieval;

import com.jiadaojia.rag.config.MilvusConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.AnnSearchReq;
import io.milvus.v2.service.vector.request.HybridSearchReq;
import io.milvus.v2.service.vector.request.data.EmbeddedText;
import io.milvus.v2.service.vector.request.data.FloatVec;
import io.milvus.v2.service.vector.request.ranker.RRFRanker;
import io.milvus.v2.service.vector.response.SearchResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 混合检索器（向量 ANN + BM25 全文检索 → RRF 融合）
 *
 * Milvus 2.5 原生 hybridSearch（v2.5.x API）：
 * - 路1 dense: FloatVec 向量 ANN
 * - 路2 sparse: EmbeddedText 查询文本，Milvus 内部 BM25 Function 自动分词+生成稀疏向量
 * - RRFRanker 融合两路结果（new RRFRanker(k)）
 *
 * v2.5.x API 要点：.ranker() 非 .functionScore()，.topK() 非 .limit()，.outFields() 非 .outputFields()
 */
@Slf4j
@Component
public class HybridRetriever {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${milvus.collection}")
    private String collectionName;

    public HybridRetriever(MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 混合检索入口
     * @param query          原始查询文本
     * @param keywords       BM25 关键词（由 QueryRewriter 提取，当前直接用 query 走全文检索）
     * @param topK           最终返回数
     * @param metadataFilter 元数据过滤（当前未用，预留）
     */
    public List<TextSegment> search(String query, List<String> keywords,
                                     int topK, dev.langchain4j.store.embedding.filter.Filter metadataFilter) {
        try {
            // 向量化查询
            Embedding queryEmbedding = embeddingModel.embed(query).content();

            // 路1: dense 向量检索（.topK 非 .limit）
            AnnSearchReq denseReq = AnnSearchReq.builder()
                    .vectorFieldName(MilvusConfig.FIELD_DENSE)
                    .vectors(List.of(new FloatVec(queryEmbedding.vector())))
                    .params("{\"ef\": 64}")
                    .topK(topK * 2)
                    .build();

            // 路2: BM25 全文检索（EmbeddedText 传查询文本，Milvus 内部自动分词+BM25）
            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(MilvusConfig.FIELD_SPARSE)
                    .vectors(List.of(new EmbeddedText(query)))
                    .topK(topK * 2)
                    .build();

            // RRF 融合（v2.5.x：.ranker(new RRFRanker(k)) 非 .functionScore()）
            HybridSearchReq hybridReq = HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(List.of(denseReq, sparseReq))
                    .ranker(new RRFRanker(60))
                    .topK(topK)
                    .outFields(List.of(MilvusConfig.FIELD_TEXT, MilvusConfig.FIELD_SOURCE))
                    .build();

            SearchResp resp = milvusClient.hybridSearch(hybridReq);
            List<List<SearchResp.SearchResult>> results = resp.getSearchResults();

            // 转为 TextSegment
            List<TextSegment> segments = new ArrayList<>();
            if (results != null && !results.isEmpty()) {
                for (SearchResp.SearchResult r : results.get(0)) {
                    Map<String, Object> entity = r.getEntity();
                    Object textVal = entity.get(MilvusConfig.FIELD_TEXT);
                    if (textVal != null) {
                        TextSegment seg = TextSegment.from(textVal.toString());
                        Object sourceVal = entity.get(MilvusConfig.FIELD_SOURCE);
                        seg.metadata().put("source", sourceVal != null ? sourceVal.toString() : "未知");
                        segments.add(seg);
                    }
                }
            }
            log.info("[混合检索] dense+sparse BM25 RRF融合={} 条", segments.size());
            return segments;
        } catch (Exception e) {
            log.error("[混合检索] 失败，降级返回空: {}", e.getMessage(), e);
            return List.of();
        }
    }
}
