package com.zzp.rag.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 飞书 HTTP 客户端 — tenant_access_token（缓存）+ 消息回复。
 * <p>
 * 长连接模式：事件走 WS，但 token 与回复仍是 HTTP API，两条腿都走 baseUrl（可切私有化）。
 * 软依赖：任何 HTTP 失败只记日志返回 null/false，对话链路不因此崩溃。
 */
@Slf4j
@Component
public class FeishuClient {

    private final FeishuConfig config;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** tenant_access_token 缓存（有效期约 2h，提前 2 分钟失效） */
    private volatile String cachedToken;
    private volatile long tokenExpireAtMs;

    public FeishuClient(FeishuConfig config) {
        this.config = config;
    }

    /** 获取 tenant_access_token（缓存命中直接返回；失败返回 null） */
    public synchronized String tenantAccessToken() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAtMs) {
            return cachedToken;
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "app_id", config.appId(),
                    "app_secret", config.appSecret()));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/auth/v3/tenant_access_token/internal"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            if (node.path("code").asInt() == 0 && node.has("tenant_access_token")) {
                cachedToken = node.get("tenant_access_token").asText();
                long expireSec = node.path("expire").asLong(7200);
                tokenExpireAtMs = now + (expireSec - 120) * 1000;
                log.info("[飞书] tenant_access_token 已获取（{} 秒有效）", expireSec);
                return cachedToken;
            }
            log.warn("[飞书] token 获取失败: {}", truncate(resp.body(), 200));
            return null;
        } catch (Exception e) {
            log.warn("[飞书] token 请求异常: {}", e.getMessage());
            return null;
        }
    }

    /** 回复消息（text 类型；失败返回 false，只记日志） */
    public boolean replyMessage(String messageId, String text) {
        String token = tenantAccessToken();
        if (token == null) {
            return false;
        }
        try {
            String content = objectMapper.writeValueAsString(java.util.Map.of("text", text));
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "msg_type", "text",
                    "content", content));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/im/v1/messages/"
                                    + java.net.URLEncoder.encode(messageId, StandardCharsets.UTF_8)
                                    + "/reply"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[飞书] 回复失败 http={} body={}", resp.statusCode(), truncate(resp.body(), 200));
                return false;
            }
            JsonNode node = objectMapper.readTree(resp.body());
            if (node.path("code").asInt() != 0) {
                log.warn("[飞书] 回复失败 code={} msg={}", node.path("code").asInt(), node.path("msg").asText());
                return false;
            }
            return true;
        } catch (Exception e) {
            log.warn("[飞书] 回复异常: {}", e.getMessage());
            return false;
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
