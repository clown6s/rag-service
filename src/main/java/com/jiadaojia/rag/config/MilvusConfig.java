package com.jiadaojia.rag.config;

import io.milvus.common.clientenum.FunctionType;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.common.ConsistencyLevel;
import io.milvus.v2.common.DataType;
import io.milvus.v2.common.IndexParam;
import io.milvus.v2.service.collection.request.AddFieldReq;
import io.milvus.v2.service.collection.request.CreateCollectionReq;
import io.milvus.v2.service.collection.request.HasCollectionReq;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 *
 * 原生 MilvusClientV2，建带 BM25 全文检索的 collection：
 * - id: 主键自增
 * - text: VARCHAR，启用中文分词 + 关键词匹配（BM25 基础）
 * - sparse_bm25: SparseFloatVector，由 Function 自动从 text 生成
 * - dense: FloatVector (BGE-M3 1024维)
 * - document_id / title / source: 标量字段，用于过滤和展示
 *
 */
@Slf4j
@Configuration
public class MilvusConfig {

    @Value("${milvus.host}")
    private String host;

    @Value("${milvus.port}")
    private int port;

    @Value("${milvus.database}")
    private String database;

    @Value("${milvus.collection}")
    private String collection;

    @Value("${milvus.dimension}")
    private int dimension;

    @Value("${milvus.username:}")
    private String username;

    @Value("${milvus.password:}")
    private String password;

    @Value("${milvus.hnsw.m:32}")
    private int hnswM;

    @Value("${milvus.hnsw.ef-construction:200}")
    private int hnswEfConstruction;

    // 字段名常量（供 IngestionPipeline/HybridRetriever/KnowledgeBaseController 引用）
    public static final String FIELD_ID = "id";
    public static final String FIELD_TEXT = "text";
    public static final String FIELD_SPARSE = "sparse_bm25";
    public static final String FIELD_DENSE = "dense";
    public static final String FIELD_DOCUMENT_ID = "document_id";
    public static final String FIELD_TITLE = "title";
    public static final String FIELD_SOURCE = "source";

    @Bean
    public MilvusClientV2 milvusClient() {
        var builder = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(database);
        if (username != null && !username.isBlank()) {
            builder.username(username).password(password);
            log.info("[Milvus] 启用鉴权连接 host={} db={}", host, database);
        } else {
            log.warn("[Milvus] 未配置鉴权，生产建议开启 RBAC");
        }
        MilvusClientV2 client = new MilvusClientV2(builder.build());
        ensureCollectionExists(client);
        return client;
    }

    /**
     * 建 BM25 collection（若不存在）
     * 首次部署时若 collection 是旧 MilvusEmbeddingStore 建的简单 dense schema，
     * 需先通过 reindex API 或手动 drop 旧 collection 再启动，以重建为 BM25 schema
     */
    public void ensureCollectionExists(MilvusClientV2 client) {
        try {
            boolean exists = client.hasCollection(HasCollectionReq.builder()
                    .collectionName(collection).build());
            if (exists) {
                log.info("[Milvus] collection 已存在: {}（若非 BM25 schema 请先 drop 重建）", collection);
                return;
            }

            CreateCollectionReq.CollectionSchema schema = CreateCollectionReq.CollectionSchema.builder().build();
            schema.addField(AddFieldReq.builder().fieldName(FIELD_ID).dataType(DataType.Int64)
                    .isPrimaryKey(true).autoID(true).build());
            // text 字段：启用中文分词 + 关键词匹配（BM25 全文检索基础）
            schema.addField(AddFieldReq.builder().fieldName(FIELD_TEXT).dataType(DataType.VarChar)
                    .maxLength(65535)
                    .enableAnalyzer(true)
                    .enableMatch(true)
                    .analyzerParams(Map.of("type", "chinese"))
                    .build());
            // sparse_bm25：由 Function 自动从 text 生成
            schema.addField(AddFieldReq.builder().fieldName(FIELD_SPARSE)
                    .dataType(DataType.SparseFloatVector).build());
            // dense：BGE-M3 向量
            schema.addField(AddFieldReq.builder().fieldName(FIELD_DENSE)
                    .dataType(DataType.FloatVector).dimension(dimension).build());
            // 标量字段（P1-13：document_id 独立标量，支持精确过滤删除）
            schema.addField(AddFieldReq.builder().fieldName(FIELD_DOCUMENT_ID)
                    .dataType(DataType.VarChar).maxLength(64).build());
            schema.addField(AddFieldReq.builder().fieldName(FIELD_TITLE)
                    .dataType(DataType.VarChar).maxLength(512).build());
            schema.addField(AddFieldReq.builder().fieldName(FIELD_SOURCE)
                    .dataType(DataType.VarChar).maxLength(512).build());

            // BM25 Function：text → sparse_bm25（Milvus 自动分词+生成稀疏向量）
            schema.addFunction(CreateCollectionReq.Function.builder()
                    .functionType(FunctionType.BM25)
                    .name("text_bm25")
                    .inputFieldNames(List.of(FIELD_TEXT))
                    .outputFieldNames(List.of(FIELD_SPARSE))
                    .build());

            // 索引
            List<IndexParam> indexes = new ArrayList<>();
            indexes.add(IndexParam.builder().fieldName(FIELD_DENSE)
                    .indexType(IndexParam.IndexType.HNSW)
                    .metricType(IndexParam.MetricType.COSINE)
                    .extraParams(Map.of("M", hnswM, "efConstruction", hnswEfConstruction))
                    .build());
            indexes.add(IndexParam.builder().fieldName(FIELD_SPARSE)
                    .indexType(IndexParam.IndexType.SPARSE_INVERTED_INDEX)
                    .metricType(IndexParam.MetricType.BM25)
                    .build());

            client.createCollection(CreateCollectionReq.builder()
                    .collectionName(collection)
                    .collectionSchema(schema)
                    .indexParams(indexes)
                    .consistencyLevel(ConsistencyLevel.BOUNDED)
                    .build());
            log.info("[Milvus] 已创建 BM25 collection: {}", collection);
        } catch (Exception e) {
            log.error("[Milvus] 建 collection 失败", e);
            throw new IllegalStateException("建 Milvus collection 失败: " + e.getMessage(), e);
        }
    }
}
