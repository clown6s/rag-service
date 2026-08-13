package com.zzp.rag.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis 会话记忆存储（自研）
 * <p>
 * LangChain4j 1.17 官方无 Redis store 模块（langchain4j-redis 停更于 1.0.0-alpha1），
 * 按官方接口自研：ChatMemoryStore 三方法 + core 自带 ChatMessageSerializer/Deserializer
 * （Jackson 多态序列化，含消息类型信息）。
 * <p>
 * 键格式：rag:chat-memory:{conversationId}，值：消息列表 JSON。
 * Redis 不可用时由上层 {@code FallbackChatMemoryStore} 降级（软依赖）。
 */
public class JedisChatMemoryStore implements ChatMemoryStore {

    private static final String KEY_PREFIX = "rag:chat-memory:";

    private final JedisPooled jedis;

    public JedisChatMemoryStore(String host, int port, String password, int db) {
        HostAndPort address = new HostAndPort(host, port);
        DefaultJedisClientConfig config = DefaultJedisClientConfig.builder()
                .database(db)
                .connectionTimeoutMillis(5000)
                .timeoutMillis(3000)
                .password(password != null && !password.isBlank() ? password : null)
                .build();
        this.jedis = new JedisPooled(address, config);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        String json = jedis.get(KEY_PREFIX + memoryId);
        if (json == null || json.isEmpty()) {
            return new ArrayList<>();
        }
        return ChatMessageDeserializer.messagesFromJson(json);
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        jedis.set(KEY_PREFIX + memoryId, ChatMessageSerializer.messagesToJson(messages));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jedis.del(KEY_PREFIX + memoryId);
    }
}
