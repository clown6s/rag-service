package com.jiadaojia.rag.config;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Milvus 向量数据库配置
 * - LangChain4j MilvusEmbeddingStore: 封装 Embedding 存取
 * - MilvusClientV2: 原生 SDK，用于管理操作和混合检索
 */
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

    @Bean
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .databaseName(database)
                .collectionName(collection)
                .dimension(dimension)
                .build();
    }

    @Bean
    public MilvusClientV2 milvusClient() {
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(database)
                .build();
        return new MilvusClientV2(config);
    }
}
