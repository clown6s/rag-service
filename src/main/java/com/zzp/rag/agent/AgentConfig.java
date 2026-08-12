package com.zzp.rag.agent;

import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.service.AiServices;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Agent 装配 — AiServices 工具循环
 * <p>
 * - chatModel / streamingChatModel: DeepSeek V3（支持 function calling，工具循环主力）
 * - 会话记忆：按 conversationId 隔离的滑动窗口（12 条），替代前端传 history
 * - maxSequentialToolsInvocations: 单轮最多连续工具调用次数，防死循环
 */
@Configuration
public class AgentConfig {

    @Bean
    public AgentService agentService(
            @Qualifier("chatModel") ChatModel chatModel,
            @Qualifier("streamingChatModel") StreamingChatModel streamingChatModel,
            AgentTools agentTools,
            @Value("${rag.agent.max-sequential-tool-invocations:5}") int maxToolInvocations,
            @Value("${rag.agent.memory-max-messages:12}") int memoryMaxMessages) {
        return AiServices.builder(AgentService.class)
                .chatModel(chatModel)
                .streamingChatModel(streamingChatModel)
                .tools(agentTools)
                .chatMemoryProvider(memoryId ->
                        MessageWindowChatMemory.withMaxMessages(memoryMaxMessages))
                .maxSequentialToolsInvocations(maxToolInvocations)
                .build();
    }
}
