package com.zzp.rag.agent;

import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.QueryReq;
import io.milvus.v2.service.vector.response.QueryResp;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法名引用校验 — 防引用幻觉（确定性干预）
 * <p>
 * 问题：检索命中弱相关内容时，模型会用自身知识补全，并引用知识库外的法律名称
 * （如库内只有民法典，答案却引用了《预防未成年人犯罪法》）。
 * 对策：提取答案中《XX法》类引用 → 与知识库文档标题白名单比对 → 库外引用追加标注提示。
 * 软依赖：白名单不可用（Milvus 故障等）时不干预，不阻断回答。
 */
@Slf4j
@Component
public class LawCitationGuard {

    /** 法律类引用模式（《...法/条例/规定/办法/细则/意见/规则》） */
    private static final Pattern LAW_CITATION = Pattern.compile(
            "《([^》]{2,40}?(?:法|条例|规定|办法|细则|意见|规则))》");

    private static final long REFRESH_INTERVAL_MS = 5 * 60 * 1000L;

    private final MilvusClientV2 milvusClient;

    @Value("${milvus.collection}")
    private String collectionName;

    @Value("${rag.citation-guard.enabled:true}")
    private boolean enabled;

    private volatile Set<String> knownTitles = Set.of();
    private volatile long lastRefreshAt = 0;

    public LawCitationGuard(MilvusClientV2 milvusClient) {
        this.milvusClient = milvusClient;
    }

    /**
     * 校验答案中的法律引用
     * @return 标注文本（无库外引用返回 null，由调用方决定是否追加）
     */
    public String validate(String answer) {
        if (!enabled || answer == null || answer.isBlank()) {
            return null;
        }
        Set<String> known = refreshIfNeeded();
        if (known.isEmpty()) {
            return null;
        }

        Set<String> unknown = new LinkedHashSet<>();
        Matcher m = LAW_CITATION.matcher(answer);
        while (m.find()) {
            String cited = m.group(1);
            boolean isKnown = known.stream()
                    .anyMatch(t -> t.contains(cited) || cited.contains(t));
            if (!isKnown) {
                unknown.add(cited);
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return "\n\n> 提示：以上回答中引用的《" + String.join("》《", unknown)
                + "》未收录于当前知识库，相关表述可能超出资料范围，请以知识库内容为准。";
    }

    /** 从 Milvus 拉取文档标题集合（5 分钟缓存；失败返回空集合 → 不干预） */
    private Set<String> refreshIfNeeded() {
        long now = System.currentTimeMillis();
        if (now - lastRefreshAt < REFRESH_INTERVAL_MS && !knownTitles.isEmpty()) {
            return knownTitles;
        }
        synchronized (this) {
            if (now - lastRefreshAt >= REFRESH_INTERVAL_MS || knownTitles.isEmpty()) {
                try {
                    QueryResp resp = milvusClient.query(QueryReq.builder()
                            .collectionName(collectionName)
                            .outputFields(List.of("title"))
                            .filter("id >= 0")
                            .limit(10_000)
                            .build());
                    Set<String> titles = new HashSet<>();
                    for (QueryResp.QueryResult r : resp.getQueryResults()) {
                        Object t = r.getEntity().get("title");
                        if (t != null && !t.toString().isBlank()) {
                            titles.add(t.toString());
                        }
                    }
                    knownTitles = titles;
                    lastRefreshAt = System.currentTimeMillis();
                    log.info("[引用校验] 知识库标题白名单刷新: {} 个", titles.size());
                } catch (Exception e) {
                    log.warn("[引用校验] 白名单刷新失败（不干预）: {}", e.getMessage());
                }
            }
        }
        return knownTitles;
    }
}
