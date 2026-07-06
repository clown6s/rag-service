package com.jiadaojia.rag.config;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * LLM 配置
 * - DeepSeek V3 (deepseek-chat): 主力对话 + 上下文摘要 + Query改写
 * - DeepSeek R1 (deepseek-reasoner): Agentic 路由推理
 */
@Configuration
public class LLMConfig {

    @Value("${langchain4j.open-ai.deepseek.api-key}")
    private String apiKey;

    @Value("${langchain4j.open-ai.deepseek.base-url}")
    private String baseUrl;

    // ==================== Chat Model (V3) ====================

    @Bean
    @Qualifier("chatModel")
    public ChatLanguageModel chatModel(
            @Value("${langchain4j.open-ai.deepseek.chat-model.model-name}") String model,
            @Value("${langchain4j.open-ai.deepseek.chat-model.temperature}") double temperature,
            @Value("${langchain4j.open-ai.deepseek.chat-model.max-tokens}") int maxTokens,
            @Value("${langchain4j.open-ai.deepseek.chat-model.timeout}") String timeout) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.parse("PT" + timeout))
                .build();
    }

    @Bean
    @Qualifier("streamingChatModel")
    public StreamingChatLanguageModel streamingChatModel(
            @Value("${langchain4j.open-ai.deepseek.chat-model.model-name}") String model,
            @Value("${langchain4j.open-ai.deepseek.chat-model.temperature}") double temperature,
            @Value("${langchain4j.open-ai.deepseek.chat-model.max-tokens}") int maxTokens,
            @Value("${langchain4j.open-ai.deepseek.chat-model.timeout}") String timeout) {
        return OpenAiStreamingChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.parse("PT" + timeout))
                .build();
    }

    // ==================== Reasoner Model (R1) ====================

    @Bean
    @Qualifier("reasonerModel")
    public ChatLanguageModel reasonerModel(
            @Value("${langchain4j.open-ai.deepseek.reasoner.model-name}") String model,
            @Value("${langchain4j.open-ai.deepseek.reasoner.temperature}") double temperature,
            @Value("${langchain4j.open-ai.deepseek.reasoner.max-tokens}") int maxTokens,
            @Value("${langchain4j.open-ai.deepseek.reasoner.timeout}") String timeout) {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName(model)
                .temperature(temperature)
                .maxTokens(maxTokens)
                .timeout(Duration.parse("PT" + timeout))
                .build();
    }

    /**
     * 用于 Contextual Chunker 的轻量摘要模型（复用 V3）
     */
    @Bean
    @Qualifier("contextualChatModel")
    public ChatLanguageModel contextualChatModel() {
        return OpenAiChatModel.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .modelName("deepseek-chat")
                .temperature(0.1)
                .maxTokens(256)
                .timeout(Duration.ofSeconds(30))
                .build();
    }
}
