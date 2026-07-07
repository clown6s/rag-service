package com.jiadaojia.rag.config;

import dev.langchain4j.store.embedding.milvus.MilvusEmbeddingStore;
import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;

/**
 * Milvus 向量数据库配置
 * - LangChain4j MilvusEmbeddingStore: 封装 Embedding 存取
 * - MilvusClientV2: 原生 SDK，用于管理操作和混合检索
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

    @Bean
    @Lazy
    public MilvusEmbeddingStore milvusEmbeddingStore() {
        // TODO: langchain4j 1.x 升级后补充 username/password 鉴权（0.36.2 的 builder 暂不支持）
        return MilvusEmbeddingStore.builder()
                .host(host)
                .port(port)
                .databaseName(database)
                .collectionName(collection)
                .dimension(dimension)
                .build();
    }

    @Bean
    @Lazy
    public MilvusClientV2 milvusClient() {
        // TODO: Milvus RBAC 鉴权 API 待确认（ConnectConfig.username/password 或 token），
        //       暂用无鉴权连接。生产启用 Milvus RBAC 后需补充鉴权配置。
        if (username != null && !username.isBlank()) {
            log.warn("[Milvus] 已配置 username 但鉴权 API 待实现，当前为无鉴权连接");
        }
        ConnectConfig config = ConnectConfig.builder()
                .uri("http://" + host + ":" + port)
                .dbName(database)
                .build();
        return new MilvusClientV2(config);
    }
}
