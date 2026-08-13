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

    /** 条文号引用模式：《X法》第N条（阿拉伯/中文数字均可） */
    private static final Pattern LAW_ARTICLE = Pattern.compile(
            "《([^》]{2,40}?(?:法|条例|规定|办法|细则|意见|规则))》第([零一二三四五六七八九十百0-9]+)条");

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
        Set<String> unknownArticles = new LinkedHashSet<>();
        Matcher m = LAW_ARTICLE.matcher(answer);
        while (m.find()) {
            String law = m.group(1);
            String article = m.group(2);
            boolean lawKnown = known.stream()
                    .anyMatch(t -> t.contains(law) || law.contains(t));
            if (!lawKnown) {
                unknown.add(law);
                continue;   // 法本身库外，条文号无需再查
            }
            // 法在库内 → 校验条文号是否真实存在（laws 模板按"第X条"切分，LIKE 精确可查）
            if (!articleExists(law, article)) {
                unknownArticles.add(law + "第" + article + "条");
            }
        }
        // 补充纯法名引用（无条文号的）
        Matcher m2 = LAW_CITATION.matcher(answer);
        while (m2.find()) {
            String cited = m2.group(1);
            boolean isKnown = known.stream()
                    .anyMatch(t -> t.contains(cited) || cited.contains(t));
            if (!isKnown) {
                unknown.add(cited);
            }
        }

        if (unknown.isEmpty() && unknownArticles.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("\n\n> 提示：以上回答存在引用校验问题——");
        if (!unknown.isEmpty()) {
            sb.append("《").append(String.join("》《", unknown)).append("》未收录于当前知识库；");
        }
        if (!unknownArticles.isEmpty()) {
            sb.append(String.join("、", unknownArticles)).append(" 未在知识库中找到对应条款（条文号可能引用有误）；");
        }
        sb.append("请以知识库内容为准。");
        return sb.toString();
    }

    /** 条文号存在性校验：知识库中该法是否存在"第N条"chunk（中文数字形式） */
    private boolean articleExists(String lawName, String articleNo) {
        try {
            String cn = toChineseNumeral(articleNo);
            String filter = "title like \"%" + lawName + "%\" and text like \"%第" + cn + "条%\"";
            QueryResp resp = milvusClient.query(QueryReq.builder()
                    .collectionName(collectionName)
                    .outputFields(List.of("id"))
                    .filter(filter)
                    .limit(1)
                    .build());
            return !resp.getQueryResults().isEmpty();
        } catch (Exception e) {
            log.debug("[引用校验] 条文号查询失败 {} 第{}条: {}", lawName, articleNo, e.getMessage());
            return true;   // 查询失败不误伤（软依赖）
        }
    }

    /** 阿拉伯数字 → 中文数字（1-9999；已为中文则原样返回） */
    private String toChineseNumeral(String s) {
        if (s == null || s.isEmpty()) return s;
        if (!s.chars().allMatch(Character::isDigit)) return s;
        int n = Integer.parseInt(s);
        if (n <= 0) return s;
        String[] digits = {"零", "一", "二", "三", "四", "五", "六", "七", "八", "九"};
        String[] units = {"", "十", "百", "千"};
        StringBuilder sb = new StringBuilder();
        int pos = 0;
        boolean zeroPending = false;
        for (char c : s.toCharArray()) {
            int d = c - '0';
            int unitPos = s.length() - 1 - pos;
            if (d == 0) {
                zeroPending = sb.length() > 0;
            } else {
                if (zeroPending) {
                    sb.append('零');
                    zeroPending = false;
                }
                sb.append(digits[d]).append(units[unitPos]);
            }
            pos++;
        }
        String result = sb.toString();
        // 中文数字习惯："一十" → "十"
        if (result.startsWith("一十")) {
            result = result.substring(1);
        }
        return result;
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
