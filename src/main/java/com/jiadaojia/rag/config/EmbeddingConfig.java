package com.jiadaojia.rag.config;

import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Embedding 模型配置 — BGE-M3
 * 1024维，中文SOTA，8192 token上下文
 */
@Configuration
public class EmbeddingConfig {

    @Value("${langchain4j.embedding.base-url}")
    private String baseUrl;

    @Value("${langchain4j.embedding.api-key}")
    private String apiKey;

    @Value("${langchain4j.embedding.model}")
    private String modelName;

    @Value("${langchain4j.embedding.dimension}")
    private int dimension;

    @Bean
    public EmbeddingModel embeddingModel() {
        return OpenAiEmbeddingModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(modelName)
                .timeout(Duration.ofSeconds(30))
                .maxRetries(3)
                .build();
    }
}
