package com.zzp.rag.config;

import com.zzp.rag.memory.JedisChatMemoryStore;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话记忆存储 — Redis 持久化（软依赖）
 * <p>
 * 默认 Redis 持久化：会话跨应用重启保留（数字员工多轮对话的前提）。
 * Redis 不可用/初始化失败时降级进程内存储（会话在进程内仍可用，仅丢失跨重启能力），
 * 遵循"软依赖不阻断主链路"原则。
 */
@Slf4j
@Configuration
public class MemoryConfig {

    @Bean
    public ChatMemoryStore chatMemoryStore(
            @Value("${rag.memory.redis.enabled:true}") boolean enabled,
            @Value("${rag.memory.redis.host:127.0.0.1}") String host,
            @Value("${rag.memory.redis.port:6379}") int port,
            @Value("${rag.memory.redis.password:}") String password,
            @Value("${rag.memory.redis.db:0}") int db) {
        if (!enabled) {
            log.info("[记忆] Redis 持久化关闭，使用进程内记忆");
            return null;   // null → AgentConfig 走纯内存
        }
        try {
            log.info("[记忆] Redis 持久化启用: {}:{}", host, port);
            return new FallbackChatMemoryStore(
                    new JedisChatMemoryStore(host, port, password, db));
        } catch (Exception e) {
            log.warn("[记忆] Redis 初始化失败，降级进程内记忆: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 软依赖兜底：Redis 读写失败时降级进程内存储（会话在进程内仍可用）
     */
    @Slf4j
    static class FallbackChatMemoryStore implements ChatMemoryStore {

        private final ChatMemoryStore delegate;
        private final Map<Object, List<ChatMessage>> fallback = new ConcurrentHashMap<>();

        FallbackChatMemoryStore(ChatMemoryStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public List<ChatMessage> getMessages(Object memoryId) {
            try {
                return delegate.getMessages(memoryId);
            } catch (Exception e) {
                log.warn("[记忆] Redis 读取失败，降级进程内: {}", e.getMessage());
                return fallback.getOrDefault(memoryId, List.of());
            }
        }

        @Override
        public void updateMessages(Object memoryId, List<ChatMessage> messages) {
            try {
                delegate.updateMessages(memoryId, messages);
            } catch (Exception e) {
                log.warn("[记忆] Redis 写入失败，降级进程内: {}", e.getMessage());
                fallback.put(memoryId, messages);
            }
        }

        @Override
        public void deleteMessages(Object memoryId) {
            try {
                delegate.deleteMessages(memoryId);
            } catch (Exception e) {
                log.warn("[记忆] Redis 删除失败: {}", e.getMessage());
            }
            fallback.remove(memoryId);
        }
    }
}
