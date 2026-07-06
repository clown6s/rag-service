package com.jiadaojia.rag.web;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import io.milvus.v2.service.collection.request.GetCollectionStatsReq;
import io.milvus.v2.service.collection.response.GetCollectionStatsResp;
import io.milvus.v2.service.vector.request.DeleteReq;
import io.milvus.v2.service.vector.response.DeleteResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.web.bind.annotation.*;

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

    public KnowledgeBaseController(@Lazy MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    @Value("${milvus.collection}")
    private String collectionName;

    @Value("${milvus.dimension}")
    private int dimension;

    @Value("${milvus.index-type}")
    private String indexType;

    public record DeleteResult(String docId, long chunksRemoved) {}
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
                    .filter("metadata like '%\"document_id\":\"" + docId + "\"%'")
                    .build());
            long deleted = resp.getDeleteCnt();
            log.info("[管理] 删除文档 docId={} 删除chunks={}", docId, deleted);
            return ApiResponse.ok(new DeleteResult(docId, deleted));
        } catch (Exception e) {
            log.error("[管理] 删除文档失败 docId={}", docId, e);
            return ApiResponse.fail("删除失败: " + e.getMessage());
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
            log.info("[管理] 索引已清空");
            return ApiResponse.ok(new ReindexResult("索引已清空，请重新摄入文档"));
        } catch (Exception e) {
            log.error("[管理] 清空索引失败", e);
            return ApiResponse.fail("清空索引失败: " + e.getMessage());
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
            return ApiResponse.ok(new StatsResult(
                    collectionName, "ACTIVE", rowCount, rowCount, dimension, indexType));
        } catch (Exception e) {
            return ApiResponse.ok(new StatsResult(
                    collectionName, "DISCONNECTED", 0, 0, dimension, indexType));
        }
    }
}
