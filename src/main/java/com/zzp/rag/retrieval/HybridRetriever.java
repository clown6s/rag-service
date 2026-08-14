package com.zzp.rag.retrieval;

import com.zzp.rag.config.MilvusConfig;
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
 * <p>
 * Milvus 2.5 原生 hybridSearch（v2.5.x API）：
 * - 路1 dense: FloatVec 向量 ANN
 * - 路2 sparse: EmbeddedText 查询文本，Milvus 内部 BM25 Function 自动分词+生成稀疏向量
 * - RRFRanker 融合两路结果（new RRFRanker(k)）
 * <p>
 * v2.5.x API 要点：.ranker() 非 .functionScore()，.topK() 非 .limit()，.outFields() 非 .outputFields()
 */
@Slf4j
@Component
public class HybridRetriever {

    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${milvus.collection}")
    private String collectionName;

    @Value("${rag.retrieval.rrf-k:60}")
    private int rrfK;

    @Value("${rag.retrieval.hnsw-ef:128}")
    private int hnswEf;

    public HybridRetriever(MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    /**
     * 混合检索入口
     * @param query          原始查询文本
     * @param expandedQuery  改写后的查询文本（短查询时用于加路检索，null 则只用原 query）
     * @param keywords       BM25 关键词（由 QueryRewriter 提取）
     * @param topK           最终返回数
     * @param filterExpr     Milvus 布尔过滤表达式（如 document_id in ["..."...]；null 不过滤。
     *                       数字员工 knowledgeScope 经此生效：每条检索路各自过滤）
     */
    public List<TextSegment> search(String query, String expandedQuery, List<String> keywords,
                                     int topK, String filterExpr) {
        try {
            // ========== 路检索策略 ==========
            // knowledgeScope 过滤：每条检索路各自带 expr（HybridSearchReq 无统一 filter 入参）

            // Dense 原query（始终启用，保证不丢原意图）
            Embedding queryEmbedding = embeddingModel.embed(query).content();
            AnnSearchReq denseOriginal = AnnSearchReq.builder()
                    .vectorFieldName(MilvusConfig.FIELD_DENSE)
                    .vectors(List.of(new FloatVec(queryEmbedding.vector())))
                    .params("{\"ef\": " + hnswEf + "}")
                    .expr(filterExpr)
                    .topK(topK * 2)
                    .build();

            // Dense expandedQuery（短查询≤4字时加路，语义扩展防单薄）
            AnnSearchReq denseExpanded = null;
            if (expandedQuery != null && !expandedQuery.isBlank() && !expandedQuery.equals(query)) {
                Embedding expandedEmbedding = embeddingModel.embed(expandedQuery).content();
                denseExpanded = AnnSearchReq.builder()
                        .vectorFieldName(MilvusConfig.FIELD_DENSE)
                        .vectors(List.of(new FloatVec(expandedEmbedding.vector())))
                        .params("{\"ef\": " + hnswEf + "}")
                        .expr(filterExpr)
                        .topK(topK * 2)
                        .build();
                log.info("[混合检索] 短查询加路: 原query={}, expandedQuery={}", query, expandedQuery);
            }

            // BM25 全文检索：短查询用 expandedQuery（更多关键词命中），长查询用原query
            String bm25QueryText = (expandedQuery != null && !expandedQuery.isBlank())
                    ? expandedQuery : query;
            EmbeddedText embeddedText = new EmbeddedText(bm25QueryText);
            AnnSearchReq sparseReq = AnnSearchReq.builder()
                    .vectorFieldName(MilvusConfig.FIELD_SPARSE)
                    .vectors(List.of(embeddedText))
                    .expr(filterExpr)
                    .topK(topK * 2)
                    .build();

            // 组合检索路（2路或3路）→ RRF 融合
            List<AnnSearchReq> searchRequests = new ArrayList<>();
            searchRequests.add(denseOriginal);
            if (denseExpanded != null) searchRequests.add(denseExpanded);
            searchRequests.add(sparseReq);

            HybridSearchReq hybridReq = HybridSearchReq.builder()
                    .collectionName(collectionName)
                    .searchRequests(searchRequests)
                    .ranker(new RRFRanker(rrfK))
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
            log.info("[混合检索] 路数={} dense+sparse BM25 RRF融合={} 条{}",
                    searchRequests.size(), segments.size(),
                    filterExpr != null ? "（过滤: " + filterExpr + "）" : "");
            return segments;
        } catch (Exception e) {
            log.error("[混合检索] 系统异常", e);
            throw new RuntimeException("混合检索失败: " + e.getMessage(), e);
        }
    }
}
