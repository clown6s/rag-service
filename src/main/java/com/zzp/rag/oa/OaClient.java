package com.zzp.rag.oa;

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

/**
 * OA 审批系统 HTTP 客户端（软依赖）：JWT 登录态维护 + 通用请求。
 * 服务地址/账号在 .env 配置（rag.oa.*）；OA 未部署时所有调用返回错误文本，不抛异常。
 */
@Slf4j
@Component
public class OaClient {

    @Value("${rag.oa.base-url:http://localhost:7000}")
    private String baseUrl;

    @Value("${rag.oa.username:admin}")
    private String username;

    @Value("${rag.oa.password:123456}")
    private String password;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();

    private volatile String cachedToken;
    private volatile long tokenExpireAtMs;

    /** 统一入口：POST/GET JSON；OA 不可达/业务失败返回 {"error": "..."} 文本 */
    public String call(String path, String method, String jsonBody) {
        try {
            String token = token();
            HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json");
            if (token != null) {
                rb.header("Authorization", "Bearer " + token);
            }
            if ("GET".equalsIgnoreCase(method)) {
                rb.GET();
            } else {
                rb.POST(HttpRequest.BodyPublishers.ofString(
                        jsonBody == null ? "{}" : jsonBody, StandardCharsets.UTF_8));
            }
            HttpResponse<String> resp = http.send(rb.build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 401) {
                cachedToken = null;   // 令牌失效，下次调用重新登录
                return "{\"error\":\"OA 令牌失效，请重试\"}";
            }
            return resp.body();
        } catch (Exception e) {
            log.warn("[OA] 调用失败 path={}: {}", path, e.getMessage());
            return "{\"error\":\"OA 服务不可达：" + e.getMessage() + "\"}";
        }
    }

    /** JWT 登录态（缓存 110 分钟，过期自动重登） */
    private synchronized String token() {
        long now = System.currentTimeMillis();
        if (cachedToken != null && now < tokenExpireAtMs) {
            return cachedToken;
        }
        try {
            String body = objectMapper.writeValueAsString(java.util.Map.of(
                    "username", username, "password", password));
            HttpRequest req = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            JsonNode node = objectMapper.readTree(resp.body());
            String token = node.path("data").asText(null);
            if (token != null && !token.isBlank()) {
                cachedToken = token;
                tokenExpireAtMs = now + 110 * 60_000L;
                log.info("[OA] 登录成功（token 已缓存）");
                return cachedToken;
            }
            log.warn("[OA] 登录失败: {}", resp.body());
            return null;
        } catch (Exception e) {
            log.warn("[OA] 登录异常: {}", e.getMessage());
            return null;
        }
    }
}
