package com.zzp.rag.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-Encoder 重排器
 * <p>
 * 调用 bge-reranker-v2-m3 API，对召回结果精排。
 * Bi-Encoder 召回（快）→ Cross-Encoder 精排（准）。
 * <p>
 * 双通道：主通道 ai-sidecar（本地推理），失败自动降级硅基流动；两者都失败才退化原始 Top-K。
 * 重排同时产出 topScore（最高相关度分数 0-1），供 Agent 兜底判定"检索是否真的命中"——
 * 召回非空不代表相关（小知识库时无关文档也会被召回）。
 */
@Slf4j
@Component
public class Reranker {

    private final RestTemplate restTemplate;

    @Value("${rag.rerank.api-url}")
    private String apiUrl;

    @Value("${rag.rerank.fallback-api-url:}")
    private String fallbackApiUrl;

    @Value("${rag.rerank.model}")
    private String model;

    @Value("${rag.retrieval.final-k:5}")
    private int finalK;

    /** 主通道（sidecar）鉴权；默认回落到 embedding 的 key（兼容旧配置） */
    @Value("${rag.rerank.api-key:${langchain4j.embedding.api-key}}")
    private String apiKey;

    /** 降级通道（硅基流动）鉴权 */
    @Value("${rag.rerank.fallback-api-key:${SILICONFLOW_API_KEY:}}")
    private String fallbackApiKey;

    public Reranker() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);  // 10s 连接超时
        factory.setReadTimeout(30_000);     // 30s 读取超时
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 对候选片段重排
     * @param query      用户查询
     * @param candidates 候选片段（召回结果）
     * @return 重排结果：segments + degraded（两通道均失败）+ topScore（最高相关度，失败为 null）
     */
    public RerankResult rerank(String query, List<TextSegment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return new RerankResult(List.of(), false, null);
        }

        List<String> documents = candidates.stream()
                .map(TextSegment::text)
                .toList();

        try {
            return doRerank(apiUrl, apiKey, query, documents, candidates);
        } catch (Exception e) {
            log.warn("[Rerank] 主通道失败，尝试降级通道: {}", e.getMessage());
            if (fallbackApiUrl != null && !fallbackApiUrl.isBlank()) {
                try {
                    return doRerank(fallbackApiUrl, fallbackApiKey, query, documents, candidates);
                } catch (Exception e2) {
                    log.warn("[Rerank] 降级通道也失败，返回原始Top-{}: {}", finalK, e2.getMessage());
                }
            } else {
                log.warn("[Rerank] 无降级通道，返回原始Top-{}", finalK);
            }
            return new RerankResult(
                    candidates.stream().limit(finalK).collect(Collectors.toList()), true, null);
        }
    }

    private RerankResult doRerank(String url, String key, String query,
                                  List<String> documents, List<TextSegment> candidates) {
        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", documents
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(key);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> resp = restTemplate.exchange(
                url, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> results =
                (List<Map<String, Object>>) Objects.requireNonNull(resp.getBody()).get("results");

        if (results == null) {
            throw new IllegalStateException("响应中无 results 字段");
        }

        List<Map<String, Object>> valid = results.stream()
                .filter(r -> r.get("relevance_score") instanceof Number
                          && r.get("index") instanceof Number)
                .toList();

        List<TextSegment> reranked = valid.stream()
                .sorted((a, b) -> Double.compare(
                        ((Number) b.get("relevance_score")).doubleValue(),
                        ((Number) a.get("relevance_score")).doubleValue()))
                .limit(finalK)
                .map(r -> {
                    int idx = ((Number) r.get("index")).intValue();
                    if (idx < 0 || idx >= candidates.size()) return null;
                    TextSegment seg = candidates.get(idx);
                    // 分数写入 metadata，供检索链路按阈值过滤无关片段（检索精度修复）
                    seg.metadata().put("rerank_score",
                            ((Number) r.get("relevance_score")).doubleValue());
                    log.debug("[Rerank] idx={} score={} source={}",
                            idx, r.get("relevance_score"),
                            seg.metadata().getString("source"));
                    return seg;
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());

        double topScore = valid.stream()
                .mapToDouble(r -> ((Number) r.get("relevance_score")).doubleValue())
                .max()
                .orElse(0.0);

        return new RerankResult(reranked, false, topScore);
    }

    /** 重排结果：segments + degraded（降级标记）+ topScore（最高相关度，供 Agent 兜底判定） */
    public record RerankResult(List<TextSegment> segments, boolean degraded, Double topScore) {}
}
