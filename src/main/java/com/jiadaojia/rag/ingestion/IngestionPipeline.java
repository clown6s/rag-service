package com.jiadaojia.rag.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 文档摄入流水线
 *
 * 6步串联：上传 → 解析 → 上下文增强 → 切分 → 向量化 → 存储
 *
 * 状态追踪：内存 ConcurrentHashMap（重启丢失，后续可换 Redis/关系型DB 持久化）
 */
@Slf4j
@Component
public class IngestionPipeline {

    private final DocumentParser parser;
    private final ContextualChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;

    /** 摄入任务状态表（内存，重启丢失）。后续可换 Redis/关系型DB 持久化 */
    private final ConcurrentHashMap<String, IngestionStatus> statusMap = new ConcurrentHashMap<>();

    public IngestionPipeline(
            DocumentParser parser,
            ContextualChunker chunker,
            EmbeddingModel embeddingModel,
            @Lazy MilvusEmbeddingStore embeddingStore) {
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.embeddingStore = embeddingStore;
    }

    /**
     * 完整摄入流程
     * @return 文档 ID
     */
    public String ingest(MultipartFile file, String title, String tags) {
        String docId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename();
        log.info("[摄入] 开始 docId={} file={}", docId, fileName);
        updateStatus(docId, "PARSING", null, null);

        try {
            // Step 1+2: 上传 + 解析（Tika 自动识别格式）
            String rawText = parser.parse(file);
            log.info("[摄入] 解析完成 docId={} textLen={}", docId, rawText.length());
            updateStatus(docId, "CHUNKING", null, null);

            // 使用自定义 title 或文件名
            String docTitle = (title != null && !title.isBlank()) ? title : fileName;

            // Step 3+4: Contextual Retrieval 上下文增强 + 递归切分
            List<TextSegment> chunks = chunker.chunk(rawText, docTitle);
            log.info("[摄入] 切分完成 docId={} chunks={}", docId, chunks.size());

            // 注入文档级元数据
            for (TextSegment seg : chunks) {
                seg.metadata().put("document_id", docId);
                seg.metadata().put("title", docTitle);
                if (tags != null && !tags.isBlank()) {
                    seg.metadata().put("tags", tags);
                }
            }

            // Step 5: BGE-M3 批量向量化
            updateStatus(docId, "EMBEDDING", null, null);
            List<Embedding> embeddings = batchEmbed(chunks);
            log.info("[摄入] 向量化完成 docId={} vectors={}", docId, embeddings.size());

            // Step 6: 分批存入 Milvus
            updateStatus(docId, "STORING", null, null);
            int batchSize = 50;
            for (int i = 0; i < embeddings.size(); i += batchSize) {
                int end = Math.min(i + batchSize, embeddings.size());
                embeddingStore.addAll(embeddings.subList(i, end), chunks.subList(i, end));
            }
            log.info("[摄入] 存储完成 docId={} vectors={}", docId, embeddings.size());

            updateStatus(docId, "COMPLETED", chunks.size(), null);
            return docId;
        } catch (Exception e) {
            log.error("[摄入] 失败 docId={}", docId, e);
            updateStatus(docId, "FAILED", null, e.getMessage());
            throw e;
        }
    }

    /**
     * 查询摄入状态（P1-07 修复：查询真实状态，不再恒返回 COMPLETED）
     */
    public IngestionStatus getStatus(String docId) {
        IngestionStatus status = statusMap.get(docId);
        if (status == null) {
            return new IngestionStatus(docId, "NOT_FOUND", null, "文档ID不存在或服务已重启");
        }
        return status;
    }

    private void updateStatus(String docId, String status, Integer totalChunks, String error) {
        statusMap.put(docId, new IngestionStatus(docId, status, totalChunks, error));
    }

    public record IngestionStatus(String docId, String status, Integer totalChunks, String error) {}

    /** 硅基流动 Embedding API 单次最多 32 条 */
    private static final int EMBED_BATCH_SIZE = 32;

    private List<Embedding> batchEmbed(List<TextSegment> chunks) {
        List<Embedding> all = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i += EMBED_BATCH_SIZE) {
            int end = Math.min(i + EMBED_BATCH_SIZE, chunks.size());
            List<TextSegment> batch = chunks.subList(i, end);
            all.addAll(embeddingModel.embedAll(batch).content());
            log.debug("[摄入] 向量化批次 {}/{}", (i / EMBED_BATCH_SIZE) + 1,
                    (chunks.size() + EMBED_BATCH_SIZE - 1) / EMBED_BATCH_SIZE);
        }
        return all;
    }
}
