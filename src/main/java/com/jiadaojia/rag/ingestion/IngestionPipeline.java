package com.jiadaojia.rag.ingestion;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.jiadaojia.rag.config.MilvusConfig;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 文档摄入流水线（原生 MilvusClientV2 insert，sparse_bm25 由 Function 自动生成）
 * 6步：上传 → 解析 → 上下文增强 → 切分 → 向量化 → 存储
 * 状态追踪：内存 LRU Map + 文件持久化（启动时加载，更新时写入）
 */
@Slf4j
@Component
public class IngestionPipeline {

    private final DocumentParser parser;
    private final ContextualChunker chunker;
    private final EmbeddingModel embeddingModel;
    private final MilvusClientV2 milvusClient;
    private final Gson gson = new Gson();

    @Value("${milvus.collection}")
    private String collectionName;

    @Value("${rag.ingestion.status-file:data/ingestion-status.json}")
    private String statusFilePath;

    private static final int MAX_STATUS = 1000;
    private final java.util.Map<String, IngestionStatus> statusMap =
            java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>(128, 0.75f, true) {
                @Override protected boolean removeEldestEntry(java.util.Map.Entry<String, IngestionStatus> eldest) {
                    return size() > MAX_STATUS;
                }
            });

    private static final Type STATUS_MAP_TYPE = new TypeToken<Map<String, IngestionStatus>>(){}.getType();

    public IngestionPipeline(
            DocumentParser parser,
            ContextualChunker chunker,
            EmbeddingModel embeddingModel,
            MilvusClientV2 milvusClient) {
        this.parser = parser;
        this.chunker = chunker;
        this.embeddingModel = embeddingModel;
        this.milvusClient = milvusClient;
    }

    /**
     * 启动时从本地文件加载历史摄入状态，解决重启丢失问题
     */
    @PostConstruct
    public void loadStatus() {
        try {
            File file = new File(statusFilePath);
            if (file.exists()) {
                try (FileReader reader = new FileReader(file)) {
                    Map<String, IngestionStatus> loaded = gson.fromJson(reader, STATUS_MAP_TYPE);
                    if (loaded != null && !loaded.isEmpty()) {
                        statusMap.putAll(loaded);
                        log.info("[摄入] 加载历史状态 {} 条", loaded.size());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("[摄入] 加载状态文件失败: {}", e.getMessage());
        }
    }

    public String ingest(MultipartFile file, String title, String tags) {
        String docId = UUID.randomUUID().toString();
        String fileName = file.getOriginalFilename();
        log.info("[摄入] 开始 docId={} file={}", docId, fileName);
        updateStatus(docId, "PARSING", null, null);

        try {
            String rawText = parser.parse(file);
            log.info("[摄入] 解析完成 docId={} textLen={}", docId, rawText.length());
            updateStatus(docId, "CHUNKING", null, null);

            String docTitle = (title != null && !title.isBlank()) ? title : fileName;
            List<TextSegment> chunks = chunker.chunk(rawText, docTitle);
            log.info("[摄入] 切分完成 docId={} chunks={}", docId, chunks.size());

            updateStatus(docId, "EMBEDDING", null, null);
            List<Embedding> embeddings = batchEmbed(chunks);
            log.info("[摄入] 向量化完成 docId={} vectors={}", docId, embeddings.size());

            // Step 6: 存入 Milvus（原生 insert，sparse_bm25 由 BM25 Function 自动从 text 生成）
            updateStatus(docId, "STORING", null, null);
            List<JsonObject> rows = new ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                JsonObject row = new JsonObject();
                row.addProperty(MilvusConfig.FIELD_TEXT, chunks.get(i).text());
                row.add(MilvusConfig.FIELD_DENSE, gson.toJsonTree(embeddings.get(i).vector()));
                row.addProperty(MilvusConfig.FIELD_DOCUMENT_ID, docId);
                row.addProperty(MilvusConfig.FIELD_TITLE, docTitle);
                row.addProperty(MilvusConfig.FIELD_SOURCE, fileName);
                rows.add(row);
            }
            int batchSize = 200;
            for (int i = 0; i < rows.size(); i += batchSize) {
                int end = Math.min(i + batchSize, rows.size());
                milvusClient.insert(InsertReq.builder()
                        .collectionName(collectionName)
                        .data(rows.subList(i, end))
                        .build());
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

    public IngestionStatus getStatus(String docId) {
        IngestionStatus status = statusMap.get(docId);
        if (status == null) {
            return new IngestionStatus(docId, "NOT_FOUND", null, "文档ID不存在或服务已重启");
        }
        return status;
    }

    private void updateStatus(String docId, String status, Integer totalChunks, String error) {
        statusMap.put(docId, new IngestionStatus(docId, status, totalChunks, error));
        persistStatus();
    }

    /**
     * 将当前状态写入本地文件，保证重启后可恢复
     */
    private void persistStatus() {
        try {
            File file = new File(statusFilePath);
            if (file.getParentFile() != null) {
                file.getParentFile().mkdirs();
            }
            try (FileWriter writer = new FileWriter(file)) {
                gson.toJson(statusMap, writer);
            }
        } catch (Exception e) {
            log.warn("[摄入] 持久化状态文件失败: {}", e.getMessage());
        }
    }

    public record IngestionStatus(String docId, String status, Integer totalChunks, String error) {}

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
