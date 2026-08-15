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

    /** 回复交互卡片（msg_type=interactive，content 为卡片 JSON 字符串） */
    public boolean replyCard(String messageId, String cardJson) {
        return sendInteractive("/open-apis/im/v1/messages/"
                        + java.net.URLEncoder.encode(messageId, StandardCharsets.UTF_8)
                        + "/reply",
                cardJson, false);
    }

    /** 更新卡片（重新生成：原卡片原地替换） */
    public boolean updateCard(String messageId, String cardJson) {
        return sendInteractive("/open-apis/im/v1/messages/"
                        + java.net.URLEncoder.encode(messageId, StandardCharsets.UTF_8),
                cardJson, true);
    }

    /** 向群发送新消息（卡片反馈感谢等场景） */
    public boolean sendCardToChat(String chatId, String cardJson) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return false;
            }
            // ObjectMapper 序列化嵌套字符串：换行/引号/反斜杠全部正确转义（手工拼接会产出非法 JSON）
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "receive_id", chatId,
                    "msg_type", "interactive",
                    "content", cardJson));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[飞书] 卡片发送失败 http={} body={}", resp.statusCode(), truncate(resp.body(), 200));
                return false;
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return node.path("code").asInt() == 0;
        } catch (Exception e) {
            log.warn("[飞书] 卡片发送异常: {}", e.getMessage());
            return false;
        }
    }

    private boolean sendInteractive(String path, String cardJson, boolean patch) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return false;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "msg_type", "interactive",
                    "content", cardJson));
            HttpRequest req = HttpRequest.newBuilder(URI.create(config.baseUrl() + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .method(patch ? "PATCH" : "POST",
                            HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[飞书] 卡片{}失败 http={} body={}", patch ? "更新" : "回复",
                        resp.statusCode(), truncate(resp.body(), 200));
                return false;
            }
            JsonNode node = objectMapper.readTree(resp.body());
            return node.path("code").asInt() == 0;
        } catch (Exception e) {
            log.warn("[飞书] 卡片{}异常: {}", patch ? "更新" : "回复", e.getMessage());
            return false;
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }

    // ==================== 文件消息（文档生成定稿推送） ====================

    /** 上传文件到飞书素材库 → file_key（失败返回 null） */
    public String uploadFile(String fileName, byte[] bytes) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return null;
            }
            String boundary = "----ragfeishu" + System.currentTimeMillis();
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            writePart(body, boundary, "file_type", "stream");
            writePart(body, boundary, "file_name", fileName);
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"file\"; filename=\"" + fileName + "\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.write(bytes);
            body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/im/v1/files"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Authorization", "Bearer " + token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            String fileKey = node.path("data").path("file_key").asText(null);
            if (fileKey == null) {
                log.warn("[飞书] 文件上传失败: {}", truncate(resp.body(), 200));
                return null;
            }
            return fileKey;
        } catch (Exception e) {
            log.warn("[飞书] 文件上传异常: {}", e.getMessage());
            return null;
        }
    }

    private void writePart(java.io.ByteArrayOutputStream out, String boundary, String name, String value)
            throws java.io.IOException {
        out.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n"
                + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }

    /** 向会话发送文件消息（file_key 来自 uploadFile；失败返回 false） */
    public boolean sendFileMessage(String chatId, String fileKey) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return false;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "receive_id", chatId,
                    "msg_type", "file",
                    "content", objectMapper.writeValueAsString(java.util.Map.of("file_key", fileKey))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/im/v1/messages?receive_id_type=chat_id"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200
                    && objectMapper.readTree(resp.body()).path("code").asInt() == 0;
            if (!ok) {
                log.warn("[飞书] 文件消息发送失败: {}", truncate(resp.body(), 200));
            }
            return ok;
        } catch (Exception e) {
            log.warn("[飞书] 文件消息发送异常: {}", e.getMessage());
            return false;
        }
    }
}
