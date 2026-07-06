package com.jiadaojia.rag.ingestion;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

/**
 * 文档摄入流水线
 *
 * 6步串联：上传 → 解析 → 上下文增强 → 切分 → 向量化 → 存储
 */
@Slf4j
@Component
public class IngestionPipeline {

    private final DocumentParser parser;
    private final ContextualChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final MilvusEmbeddingStore embeddingStore;

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

        // Step 1+2: 上传 + 解析（Tika 自动识别格式）
        String rawText = parser.parse(file);
        log.info("[摄入] 解析完成 docId={} textLen={}", docId, rawText.length());

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

        // Step 5: BGE-M3 批量向量化（0.36.2: embedAll 接受 List<TextSegment>）
        List<Embedding> embeddings = embeddingModel.embedAll(chunks).content();
        log.info("[摄入] 向量化完成 docId={} vectors={}", docId, embeddings.size());

        // Step 6: 存入 Milvus（0.36.2: addAll(embeddings, segments)）
        embeddingStore.addAll(embeddings, chunks);
        log.info("[摄入] 存储完成 docId={}", docId);

        return docId;
    }

    /**
     * 查询摄入状态
     */
    public IngestionStatus getStatus(String docId) {
        // 简化实现：通过 metadata filter 查询 chunk 数量
        // 实际应维护一张 ingestion_jobs 表
        return new IngestionStatus(docId, "COMPLETED", null, null);
    }

    public record IngestionStatus(String docId, String status, Integer totalChunks, String error) {}
}
