package com.zzp.rag.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 飞书告警推送 — 规则触发/恢复时推送群机器人 webhook 卡片（渠道适配器之推送面）。
 * <p>
 * 软依赖贯穿：
 * - webhook-url 未配置 → 整体停用（no-op）
 * - 推送失败 → 只记日志，绝不阻断规则引擎
 * - 签名：webhook-sign-secret 配置了才走 HMAC-SHA256 签名（飞书可选安全设置）
 * - 防风暴：同规则 5 分钟内只推一次（fire 的 REFRESHED 不推，此处再兜一层）
 */
@Slf4j
@Component
public class FeishuAlertNotifier {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${rag.alert.feishu.webhook-url:}")
    private String webhookUrl;

    @Value("${rag.alert.feishu.webhook-sign-secret:}")
    private String signSecret;

    /** 单线程异步推送：不拖慢规则评估；飞书限流天然排队 */
    private final ExecutorService pusher = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "feishu-alert-push");
        t.setDaemon(true);
        return t;
    });

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    /** 同规则最小推送间隔（防风暴兜底；fire 层已按 CREATED/REOPENED 过滤） */
    private static final long MIN_INTERVAL_MS = 5 * 60 * 1000L;
    private final ConcurrentHashMap<String, Long> lastPushAt = new ConcurrentHashMap<>();

    public boolean enabled() {
        return webhookUrl != null && !webhookUrl.isBlank();
    }

    /** 告警触发/复发推送（红色/橙色卡片） */
    public void pushAlert(String ruleCode, String level, String title, String detail) {
        push(ruleCode, card(
                "CRITICAL".equals(level) ? "red" : "orange",
                (level == null || level.isBlank() ? "" : "[" + level + "] ") + title,
                ruleCode, detail, null), true);
    }

    /** 告警恢复推送（绿色卡片；状态转换一次性事件，不走防风暴去重） */
    public void pushRecovered(String ruleCode, String title) {
        push(ruleCode, card("green", "✅ 告警恢复：" + title, ruleCode, null, "规则条件已清除，告警自动恢复。"),
                false);
    }

    // ==================== 内部 ====================

    private void push(String ruleCode, String body, boolean dedup) {
        if (!enabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (dedup) {
            Long last = lastPushAt.putIfAbsent(ruleCode, now);
            if (last != null && now - last < MIN_INTERVAL_MS) {
                log.debug("[飞书告警] {} 5 分钟内已推送，跳过", ruleCode);
                return;
            }
            lastPushAt.put(ruleCode, now);
        }
        pusher.execute(() -> {
            try {
                String url = webhookUrl;
                if (signSecret != null && !signSecret.isBlank()) {
                    url = appendSign(url, signSecret);
                }
                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) {
                    log.warn("[飞书告警] 推送失败 rule={} http={} body={}",
                            ruleCode, resp.statusCode(), truncate(resp.body(), 200));
                } else {
                    log.info("[飞书告警] 已推送 rule={}", ruleCode);
                }
            } catch (Exception e) {
                log.warn("[飞书告警] 推送异常 rule={}: {}", ruleCode, e.getMessage());
            }
        });
    }

    /** 群机器人签名：timestamp + "\n" + secret → HMAC-SHA256 → Base64，追加为 query 参数 */
    private String appendSign(String url, String secret) {
        try {
            String timestamp = String.valueOf(System.currentTimeMillis() / 1000);
            String stringToSign = timestamp + "\n" + secret;
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(stringToSign.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sign = Base64.getEncoder().encodeToString(mac.doFinal());
            return url + (url.contains("?") ? "&" : "?")
                    + "timestamp=" + timestamp + "&sign=" + java.net.URLEncoder.encode(sign, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[飞书告警] 签名失败，退化为无签名: {}", e.getMessage());
            return url;
        }
    }

    /** 飞书消息卡片（interactive 卡片：头部色条 + 详情 markdown 段） */
    private String card(String color, String title, String ruleCode, String detail, String note) {
        StringBuilder md = new StringBuilder();
        if (ruleCode != null) {
            md.append("**规则**：").append(ruleCode).append("\n");
        }
        if (detail != null && !detail.isBlank()) {
            md.append("**详情**：").append(detail.replace("\n", " ")).append("\n");
        }
        if (note != null) {
            md.append(note);
        }
        md.append("**时间**：").append(java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        try {
            return objectMapper.writeValueAsString(Map.of(
                    "msg_type", "interactive",
                    "card", Map.of(
                            "header", Map.of(
                                    "title", Map.of("tag", "plain_text", "content", title),
                                    "template", color),
                            "elements", List.of(Map.of(
                                    "tag", "div",
                                    "text", Map.of("tag", "lark_md", "content", md.toString()))))));
        } catch (Exception e) {
            // 序列化不会失败（纯 Map），兜底文本消息
            return "{\"msg_type\":\"text\",\"content\":{\"text\":\"" + title + "\"}}";
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
