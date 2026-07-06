package com.jiadaojia.rag.retrieval;

import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Cross-Encoder 重排器
 *
 * 调用 bge-reranker-v2-m3 API，对召回结果精排。
 * Bi-Encoder 召回（快）→ Cross-Encoder 精排（准）。
 */
@Slf4j
@Component
public class Reranker {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${rag.rerank.api-url}")
    private String apiUrl;

    @Value("${rag.rerank.model}")
    private String model;

    @Value("${rag.retrieval.final-k:5}")
    private int finalK;

    @Value("${langchain4j.embedding.api-key}")
    private String apiKey;

    /**
     * 对候选片段重排
     * @param query      用户查询
     * @param candidates 候选片段（召回结果）
     * @return 按相关性降序排列，最多 finalK 条
     */
    public List<TextSegment> rerank(String query, List<TextSegment> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }

        if (candidates.size() <= finalK) {
            return candidates;
        }

        List<String> documents = candidates.stream()
                .map(TextSegment::text)
                .collect(Collectors.toList());

        Map<String, Object> body = Map.of(
                "model", model,
                "query", query,
                "documents", documents
        );

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(apiKey);

        try {
            @SuppressWarnings("rawtypes")
            ResponseEntity<Map> resp = restTemplate.exchange(
                    apiUrl, HttpMethod.POST,
                    new HttpEntity<>(body, headers), Map.class);

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) Objects.requireNonNull(resp.getBody()).get("results");

            return results.stream()
                    .sorted((a, b) -> Double.compare(
                            ((Number) b.get("relevance_score")).doubleValue(),
                            ((Number) a.get("relevance_score")).doubleValue()))
                    .limit(finalK)
                    .map(r -> {
                        int idx = ((Number) r.get("index")).intValue();
                        TextSegment seg = candidates.get(idx);
                        log.debug("[Rerank] idx={} score={} source={}",
                                idx, r.get("relevance_score"),
                                seg.metadata().getString("source"));
                        return seg;
                    })
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("[Rerank] API调用失败，返回原始Top-{}: {}", finalK, e.getMessage());
            return candidates.stream().limit(finalK).collect(Collectors.toList());
        }
    }
}
