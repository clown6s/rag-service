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

    // ==================== 飞书云文档（在线协作文档直出，替代文件消息） ====================

    /** 创建飞书云文档（docx）→ document_id；权限不足/失败返回 null */
    public String createCloudDoc(String title) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return null;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "title", title == null || title.isBlank() ? "文档" : title));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/docx/v1/documents"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            String docId = node.path("data").path("document").path("document_id").asText(null);
            if (docId == null) {
                log.warn("[飞书] 云文档创建失败: {}", truncate(resp.body(), 200));
                return null;
            }
            return docId;
        } catch (Exception e) {
            log.warn("[飞书] 云文档创建异常: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 写入云文档内容（blocks 批量追加到根块）。
     * blocks 元素：{"block_type":2,"text":{"elements":[{"text_run":{"content":"..."}}]}}
     * 类型：2=文本 3=一级标题 4=二级标题 12=有序列表 13=无序列表
     */
    public java.util.List<String> writeCloudDocBlocks(String documentId,
                                                      java.util.List<java.util.Map<String, Object>> blocks) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return null;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "children", blocks,
                    "index", 0));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/docx/v1/documents/"
                                    + documentId + "/blocks/" + documentId + "/children"))
                    .timeout(Duration.ofSeconds(20))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            if (resp.statusCode() != 200 || node.path("code").asInt() != 0) {
                log.warn("[飞书] 云文档写入失败: {}", truncate(resp.body(), 200));
                return null;
            }
            java.util.List<String> ids = new java.util.ArrayList<>();
            node.path("data").path("children").forEach(ch ->
                    ids.add(ch.path("block_id").asText("")));
            return ids;
        } catch (Exception e) {
            log.warn("[飞书] 云文档写入异常: {}", e.getMessage());
            return null;
        }
    }

    /** 块对齐（1=左 2=居中 3=右；fields 必须含 1 即 align 字段，实测数值枚举） */
    public boolean updateBlockAlign(String documentId, String blockId, int align) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return false;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "update_text_style", java.util.Map.of(
                            "style", java.util.Map.of("align", align),
                            "fields", java.util.List.of(1))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/docx/v1/documents/"
                                    + documentId + "/blocks/" + blockId))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200
                    && objectMapper.readTree(resp.body()).path("code").asInt() == 0;
            if (!ok) {
                log.warn("[飞书] 块对齐失败: {}", truncate(resp.body(), 150));
            }
            return ok;
        } catch (Exception e) {
            log.warn("[飞书] 块对齐异常: {}", e.getMessage());
            return false;
        }
    }

    /** 设置云文档组织内可读（成员可打开链接）；失败静默（文档仍在应用空间内）。
     *  注意：该接口为 PATCH（POST 会 404 page not found），需 drive:drive 权限 */
    public void makeCloudDocOrgReadable(String documentId) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "link_share_entity", "tenant_readable",
                    "external_access_entity", "closed",
                    "external_access", false));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/drive/v1/permissions/"
                                    + documentId + "/public?type=docx"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .method("PATCH", HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200 || objectMapper.readTree(resp.body()).path("code").asInt() != 0) {
                log.warn("[飞书] 云文档公开设置失败（不影响使用）: {}", truncate(resp.body(), 150));
            }
        } catch (Exception e) {
            log.warn("[飞书] 云文档公开设置异常: {}", e.getMessage());
        }
    }

    /** 云文档访问链接（SaaS 域名，私有化部署换 config.baseUrl 同域） */
    public String cloudDocUrl(String documentId) {
        return config.baseUrl().contains("open.feishu.cn")
                ? "https://feishu.cn/docx/" + documentId
                : config.baseUrl() + "/docx/" + documentId;
    }

    /**
     * 把用户加成云文档编辑者（tenant_readable 只给组织"可读"，不含编辑）。
     * POST drive/v1/permissions/{token}/members?type=docx，perm=full_access；
     * 失败静默（文档仍可读，仅不能在线编辑）。
     */
    public boolean addDocEditor(String documentId, String openId) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return false;
            }
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "member_type", "openid",
                    "member_id", openId,
                    "perm", "full_access"));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/drive/v1/permissions/"
                                    + documentId + "/members?type=docx"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + token)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            boolean ok = resp.statusCode() == 200
                    && objectMapper.readTree(resp.body()).path("code").asInt() == 0;
            if (!ok) {
                log.warn("[飞书] 文档编辑者添加失败: {}", truncate(resp.body(), 150));
            }
            return ok;
        } catch (Exception e) {
            log.warn("[飞书] 文档编辑者添加异常: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 文件消息（文档生成定稿推送 + 转发文件下载） ====================

    /** 下载资源（用户转发给机器人的文件/图片）→ 字节 + 推测文件名 */
    public record DownloadedFile(byte[] bytes, String fileName) {}

    /** 下载消息资源文件：GET im/v1/messages/{message_id}/resources/{file_key}?type=file */
    public DownloadedFile downloadResource(String messageId, String fileKey) {
        try {
            String token = tenantAccessToken();
            if (token == null) {
                return null;
            }
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            config.baseUrl() + "/open-apis/im/v1/messages/"
                                    + java.net.URLEncoder.encode(messageId, StandardCharsets.UTF_8)
                                    + "/resources/" + java.net.URLEncoder.encode(fileKey, StandardCharsets.UTF_8)
                                    + "?type=file"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (resp.statusCode() != 200 || resp.body() == null || resp.body().length == 0) {
                log.warn("[飞书] 文件下载失败 http={} len={}", resp.statusCode(),
                        resp.body() == null ? 0 : resp.body().length);
                return null;
            }
            String fileName = fileNameFromDisposition(
                    resp.headers().firstValue("Content-Disposition").orElse(null));
            if (fileName == null) {
                fileName = fileNameFromContentType(
                        resp.headers().firstValue("Content-Type").orElse(null));
            }
            return new DownloadedFile(resp.body(), fileName);
        } catch (Exception e) {
            log.warn("[飞书] 文件下载异常: {}", e.getMessage());
            return null;
        }
    }

    /** Content-Disposition 提取文件名（兼容 filename 与 filename*=UTF-8''） */
    private String fileNameFromDisposition(String disposition) {
        if (disposition == null) {
            return null;
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("filename\\*?=(?:UTF-8''|\")?([^\";]+)\"?", java.util.regex.Pattern.CASE_INSENSITIVE)
                .matcher(disposition);
        if (m.find()) {
            String name = m.group(1).trim();
            if (!name.isBlank()) {
                return name;
            }
        }
        return null;
    }

    /** Content-Type 反推扩展名（文件消息不带文件名时保证 DocumentParser 走对解析通道） */
    private String fileNameFromContentType(String contentType) {
        if (contentType == null) {
            return "feishu_file.bin";
        }
        String ct = contentType.toLowerCase();
        if (ct.contains("pdf")) {
            return "feishu_file.pdf";
        }
        if (ct.contains("wordprocessingml")) {
            return "feishu_file.docx";
        }
        if (ct.contains("spreadsheetml")) {
            return "feishu_file.xlsx";
        }
        if (ct.contains("presentationml")) {
            return "feishu_file.pptx";
        }
        if (ct.contains("msword")) {
            return "feishu_file.doc";
        }
        if (ct.startsWith("text/") || ct.contains("plain")) {
            return "feishu_file.txt";
        }
        if (ct.contains("csv")) {
            return "feishu_file.csv";
        }
        if (ct.contains("zip")) {
            return "feishu_file.zip";
        }
        return "feishu_file.bin";
    }

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
