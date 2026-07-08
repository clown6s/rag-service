package com.jiadaojia.rag.web;

import com.jiadaojia.rag.config.MilvusConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 知识库管理 API
 *
 * DELETE /api/rag/admin/documents/{docId} — 按文档ID删除
 * POST   /api/rag/admin/reindex          — 全量重建索引
 * GET    /api/rag/admin/stats            — 统计概览
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin")
public class KnowledgeBaseController {

    private final MilvusClientV2 milvusClient;
    private final MilvusConfig milvusConfig;

    public KnowledgeBaseController(@Lazy MilvusClientV2 milvusClient, MilvusConfig milvusConfig) {
        this.milvusClient = milvusClient;
        this.milvusConfig = milvusConfig;
    }

    @Value("${milvus.collection}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    @Value("${milvus.index-type}")
    private String indexType;

    public record DeleteResult(String docId, long chunksRemoved) {}

    public record DocumentInfo(String docId, String title) {}

    /**
     * 列出所有已摄入文档（按 document_id 去重，从 Milvus query 聚合）
     */
    @GetMapping("/documents")
    public ApiResponse<java.util.List<DocumentInfo>> listDocuments() {
        try {
            QueryResp resp = milvusClient.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter("id >= 0")  // Milvus query 需要 filter，id>=0 获取所有行
                    .outputFields(java.util.List.of("document_id", "title"))
                    .limit(1000)
                    .build());
            java.util.Map<String, DocumentInfo> docs = new java.util.LinkedHashMap<>();
            for (QueryResp.QueryResult r : resp.getQueryResults()) {
                String docId = (String) r.getEntity().get("document_id");
                String title = (String) r.getEntity().get("title");
                if (docId != null) docs.putIfAbsent(docId, new DocumentInfo(docId, title));
            }
            return ApiResponse.ok(new java.util.ArrayList<>(docs.values()));
        } catch (Exception e) {
            log.warn("[管理] 列出文档失败: {}", e.getMessage());
            return ApiResponse.ok(java.util.List.of());
        }
    }
    public record ReindexResult(String status) {}
    public record StatsResult(String collection, String status, long totalDocuments,
                              long totalChunks, int vectorDimension, String indexType) {}

    /**
     * 按文档 ID 删除全部 chunks
     */
    @DeleteMapping("/documents/{docId}")
    public ApiResponse<DeleteResult> deleteDocument(@PathVariable String docId) {
        if (!docId.matches("^[0-9a-fA-F\\-]{36}$")) {
            return ApiResponse.fail("非法的文档ID格式");
        }
        try {
            DeleteResp resp = milvusClient.delete(DeleteReq.builder()
                    .collectionName(collectionName)
                    .filter("document_id == \"" + docId + "\"")
                    .build());
            long deleted = resp.getDeleteCnt();
            log.info("[管理] 删除文档 docId={} 删除chunks={}", docId, deleted);
            return ApiResponse.ok(new DeleteResult(docId, deleted));
        } catch (Exception e) {
            log.error("[管理] 删除文档失败 docId={}", docId, e);
            return ApiResponse.fail("删除失败，请联系管理员");
        }
    }

    /**
     * 全量重建索引（清空 collection + 重新摄入）
     */
    @PostMapping("/reindex")
    public ApiResponse<ReindexResult> reindex() {
        try {
            milvusClient.dropCollection(
                    DropCollectionReq.builder()
                            .collectionName(collectionName)
                            .build());
            log.info("[管理] 索引已清空，重建 BM25 collection...");
            milvusConfig.ensureCollectionExists(milvusClient);
            return ApiResponse.ok(new ReindexResult("索引已重建（BM25 schema），请重新摄入文档"));
        } catch (Exception e) {
            log.error("[管理] 清空索引失败", e);
            return ApiResponse.fail("清空索引失败，请联系管理员");
        }
    }

    /**
     * 知识库统计概览
     */
    @GetMapping("/stats")
    public ApiResponse<StatsResult> stats() {
        try {
            GetCollectionStatsResp statsResp = milvusClient.getCollectionStats(
                    GetCollectionStatsReq.builder()
                            .collectionName(collectionName)
                            .build());
            long rowCount = statsResp.getNumOfEntities();
            log.info("[管理] 统计 collection={} rows={}", collectionName, rowCount);
            return ApiResponse.ok(new StatsResult(
                    collectionName, "ACTIVE", rowCount, rowCount, dimension, indexType));
        } catch (Exception e) {
            log.warn("[管理] 统计失败: {}", e.getMessage());
            return ApiResponse.ok(new StatsResult(
                    collectionName, "DISCONNECTED", 0, 0, dimension, indexType));
        }
    }

    /**
     * 诊断接口：按关键词搜索 Milvus chunks，查看原始数据
     * GET /api/rag/admin/debug/search?keyword=龙门&limit=10
     * GET /api/rag/admin/debug/search?source=导游面试问答.doc&limit=50  (按文件名查所有chunk)
     */
    @GetMapping("/debug/search")
    public ApiResponse<java.util.List<ChunkInfo>> debugSearch(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String source,
            @RequestParam(defaultValue = "10") int limit) {
        try {
            String filter;
            if (source != null && !source.isBlank()) {
                // 按文件名查所有chunk（不用TEXT_MATCH，直接标量过滤）
                filter = "source == \"" + source + "\"";
            } else if (keyword != null && !keyword.isBlank()) {
                filter = "TEXT_MATCH(text, '" + keyword + "')";
            } else {
                filter = "id >= 0";
            }

            QueryResp resp = milvusClient.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .filter(filter)
                    .outputFields(java.util.List.of("text", "source", "document_id", "title"))
                    .limit(limit)
                    .build());
            java.util.List<ChunkInfo> chunks = new java.util.ArrayList<>();
            for (QueryResp.QueryResult r : resp.getQueryResults()) {
                Map<String, Object> entity = r.getEntity();
                String text = (String) entity.get("text");
                String src = (String) entity.get("source");
                String docId = (String) entity.get("document_id");
                String title = (String) entity.get("title");
                // 截断过长内容，保留前300字方便查看关键词
                String preview = text != null && text.length() > 300
                        ? text.substring(0, 300) + "..." : text;
                chunks.add(new ChunkInfo(docId, title, src, preview, text != null ? text.length() : 0));
            }
            log.info("[诊断] keyword={} source={} 找到 {} 条chunks", keyword, source, chunks.size());
            return ApiResponse.ok(chunks);
        } catch (Exception e) {
            log.warn("[诊断] 搜索失败: {}", e.getMessage());
            return ApiResponse.ok(java.util.List.of());
        }
    }

    /** 诊断 chunk 信息 */
    public record ChunkInfo(String docId, String title, String source, String preview, int fullLength) {}

}
