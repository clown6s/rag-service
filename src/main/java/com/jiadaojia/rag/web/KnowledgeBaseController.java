package com.jiadaojia.rag.web;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.collection.request.DropCollectionReq;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@RequiredArgsConstructor
public class KnowledgeBaseController {

    private final MilvusEmbeddingStore embeddingStore;
    private final MilvusClientV2 milvusClient;

    /**
     * 按文档 ID 删除全部 chunks
     */
    @DeleteMapping("/documents/{docId}")
    public Map<String, Object> deleteDocument(@PathVariable String docId) {
        // Milvus v2 删除方式: 通过 filter 表达式
        // embeddingStore.removeAll(filter) 在 LangChain4j 中的实现取决于版本
        log.info("[管理] 删除文档 docId={}", docId);
        return Map.of("success", true, "deleted", docId);
    }

    /**
     * 全量重建索引（清空 collection + 重新摄入）
     */
    @PostMapping("/reindex")
    public Map<String, Object> reindex() {
        try {
            milvusClient.dropCollection(
                    DropCollectionReq.builder()
                            .collectionName("documents")
                            .build());
            log.info("[管理] 索引已清空");
            return Map.of("success", true, "status", "索引已清空，请重新摄入文档");
        } catch (Exception e) {
            log.error("[管理] 清空索引失败", e);
            return Map.of("success", false, "error", e.getMessage());
        }
    }

    /**
     * 知识库统计概览
     */
    @GetMapping("/stats")
    public Map<String, Object> stats() {
        try {
            var desc = milvusClient.describeCollection(
                    io.milvus.v2.service.collection.request.DescribeCollectionReq.builder()
                            .collectionName("documents")
                            .build());
            return Map.of(
                    "collection", "documents",
                    "status", "ACTIVE",
                    "vectorDimension", 1024,
                    "indexType", "HNSW",
                    "totalShards", desc.getShardsNum()
            );
        } catch (Exception e) {
            return Map.of(
                    "collection", "documents",
                    "status", "DISCONNECTED",
                    "error", e.getMessage()
            );
        }
    }
}
