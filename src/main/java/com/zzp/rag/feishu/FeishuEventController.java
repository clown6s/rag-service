package com.zzp.rag.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 飞书事件 HTTP 回调入口（回调模式 + 联调测试入口）。
 * <p>
 * 长连接模式下事件走 WS，此端点仍保留——与 WS 共用同一个 {@link FeishuChatBridge}，
 * 联调时可直接 POST 事件体模拟飞书推送（token 校验通过即可）。
 * <p>
 * 支持：
 * 1. URL 验证（challenge 回显）
 * 2. verification token 验签（header.token / body.token，飞书 schema 2.0 为 header.token）
 * 3. im.message.receive_v1 事件异步转桥处理（秒回 200，不阻塞飞书重试）
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/bridge/feishu")
@ConditionalOnProperty(name = "rag.feishu.enabled", havingValue = "true")
public class FeishuEventController {

    private final FeishuConfig config;
    private final FeishuChatBridge bridge;
    private final ObjectMapper objectMapper;

    private final ExecutorService executor = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "feishu-event");
        t.setDaemon(true);
        return t;
    });

    public FeishuEventController(FeishuConfig config, FeishuChatBridge bridge, ObjectMapper objectMapper) {
        this.config = config;
        this.bridge = bridge;
        this.objectMapper = objectMapper;
    }

    @PostMapping("/event")
    public Map<String, Object> event(@RequestBody JsonNode body) {
        // 1. URL 验证：回显 challenge
        if (body.has("challenge")) {
            return Map.of("challenge", body.get("challenge").asText());
        }
        // 2. 验签：header.token 优先（schema 2.0），兼容 body.token
        String token = body.path("header").path("token").asText();
        if (token.isBlank()) {
            token = body.path("token").asText();
        }
        String expected = config.verificationToken();
        if (expected != null && !expected.isBlank() && !expected.equals(token)) {
            log.warn("[飞书] 验签失败，丢弃事件");
            return Map.of("code", 401, "msg", "invalid token");
        }
        // 3. 消息事件异步转桥；卡片回调（长连接模式下走 WS，HTTP 模式此处等价支持，兼作联调入口）
        String eventType = body.path("header").path("event_type").asText();
        if ("im.message.receive_v1".equals(eventType)) {
            executor.execute(() -> {
                try {
                    bridge.handleEvent(body);
                } catch (Exception e) {
                    log.error("[飞书] 事件处理异常", e);
                }
            });
        } else if ("card.action.trigger".equals(eventType)) {
            executor.execute(() -> {
                try {
                    JsonNode ev = body.path("event");
                    String opId = ev.path("operator").path("open_id").asText(null);
                    if (opId == null || opId.isBlank()) {
                        opId = ev.path("operator").path("tenant_key").asText(null);
                    }
                    JsonNode action = ev.has("action") ? ev.path("action")
                            : ev.path("card").path("action");   // schema 2.0 为 event.action，兼容旧结构
                    java.util.Map<String, Object> value = new java.util.LinkedHashMap<>();
                    action.path("value").fields().forEachRemaining(
                            f -> value.put(f.getKey(), f.getValue().isTextual()
                                    ? f.getValue().asText() : f.getValue()));
                    java.util.Map<String, Object> formValue = new java.util.LinkedHashMap<>();
                    action.path("form_value").fields().forEachRemaining(
                            f -> formValue.put(f.getKey(), f.getValue().asText()));
                    bridge.handleCardAction(value, formValue,
                            ev.path("context").path("open_message_id").asText(),
                            ev.path("context").path("open_chat_id").asText(),
                            opId);
                } catch (Exception e) {
                    log.error("[飞书] 卡片回调处理异常", e);
                }
            });
        } else {
            log.info("[飞书] 忽略事件类型: {}", eventType);
        }
        return Map.of("code", 0);
    }
}
