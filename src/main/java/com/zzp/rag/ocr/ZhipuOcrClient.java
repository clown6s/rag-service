package com.zzp.rag.ocr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;

/**
 * 智谱 OCR（glm-4v-flash 视觉模型）：图片 → 识别文字。
 * <p>
 * 软依赖：key 未配置/额度耗尽/调用失败返回 null，上游（飞书图片消息）降级提示。
 * API：POST https://open.bigmodel.cn/api/paas/v4/chat/completions
 */
@Slf4j
@Component
public class ZhipuOcrClient {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${rag.ocr.zhipu-key:${ZHIPU_API_KEY:}}")
    private String apiKey;

    @Value("${rag.ocr.zhipu-model:glm-4v-flash}")
    private String model;

    public boolean available() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** 图片字节 → 识别文本；失败返回 null */
    public String recognize(byte[] imageBytes) {
        if (!available()) {
            return null;
        }
        try {
            String base64 = Base64.getEncoder().encodeToString(imageBytes);
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "model", model,
                    "messages", java.util.List.of(java.util.Map.of(
                            "role", "user",
                            "content", java.util.List.of(
                                    java.util.Map.of("type", "text",
                                            "text", "请识别图片中的所有文字，按原文顺序原样输出，"
                                                    + "不要添加任何解释、不要翻译；没有文字就输出「无文字」"),
                                    java.util.Map.of("type", "image_url",
                                            "image_url", java.util.Map.of("url",
                                                    "data:image/png;base64," + base64)))))));
            HttpRequest req = HttpRequest.newBuilder(URI.create(
                            "https://open.bigmodel.cn/api/paas/v4/chat/completions"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[智谱OCR] 调用失败 http={} body={}", resp.statusCode(),
                        resp.body() == null ? "" : resp.body().substring(0, Math.min(200, resp.body().length())));
                return null;
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String text = node.path("choices").path(0).path("message").path("content").asText(null);
            if (text == null || text.isBlank() || "无文字".equals(text.strip())) {
                return null;
            }
            log.info("[智谱OCR] 识别成功 {} 字", text.length());
            return text.strip();
        } catch (Exception e) {
            log.warn("[智谱OCR] 识别异常: {}", e.getMessage());
            return null;
        }
    }
}
