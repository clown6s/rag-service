package com.zzp.rag.mysql.service;

import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.mapper.AuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 知识库健康度：聚合「暂无法回答」类查询，按共享关键词聚类成主题，
 * 输出 FAQ 补录清单（运营闭环：答不上不是终点，是补录需求）。
 * <p>
 * 聚类为确定性轻量实现（无 NLP 依赖）：停用词剥离 → 2+ 字中文词提取 →
 * 共享任一关键词的问题并组（并查集）→ 组名取组内最高频关键词。
 * 软依赖：业务库不可用返回 null。
 */
@Slf4j
@Service
public class KbHealthService {

    /** 问句填充词/停用词（聚类前剥离，防"如何申请年假"与"年假几天"聚不到一起） */
    private static final Set<String> STOPWORDS = new HashSet<>(Arrays.asList(
            "什么", "怎么", "如何", "请问", "一下", "吗", "呢", "啊", "的", "了", "是", "在",
            "有", "和", "与", "或", "请", "问", "多少", "哪些", "哪个", "哪", "为什么",
            "可以", "需要", "应该", "我们", "你们", "他们", "公司", "这个", "那个", "我想",
            "我要", "知道", "了解", "介绍", "讲解", "给我", "告诉", "时候", "现在", "到底",
            "有没有", "是不是", "怎么样", "哪些", "什么用", "干嘛"));

    private final AuditEventMapper mapper;
    private final MysqlStatus status;

    public KbHealthService(AuditEventMapper mapper, MysqlStatus status) {
        this.mapper = mapper;
        this.status = status;
    }

    public record Topic(String topic, int count, List<String> samples) {}

    public record KbHealth(int totalQueries, int unanswered, double unansweredRate,
                           List<Topic> topics) {}

    public KbHealth health(int days) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            int safeDays = Math.min(Math.max(days, 1), 90);
            Map<String, Object> overview = mapper.unansweredOverview(safeDays);
            long total = num(overview.get("total"));
            long unanswered = num(overview.get("unanswered"));
            double rate = total == 0 ? 0 : unanswered * 100.0 / total;

            List<Map<String, Object>> rows = mapper.unansweredQueries(safeDays);
            // query -> (count, keywords)
            List<String> queries = new ArrayList<>();
            List<Integer> counts = new ArrayList<>();
            List<Set<String>> keywordSets = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                String q = str(row.get("query"));
                if (q == null || q.isBlank()) {
                    continue;
                }
                queries.add(q);
                counts.add((int) num(row.get("cnt")));
                keywordSets.add(extractKeywords(q));
            }

            // 并查集：共享任一关键词的问题合并为一个主题组
            int n = queries.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) {
                parent[i] = i;
            }
            Map<String, Integer> keywordFirst = new HashMap<>();
            for (int i = 0; i < n; i++) {
                for (String kw : keywordSets.get(i)) {
                    Integer first = keywordFirst.putIfAbsent(kw, i);
                    if (first != null) {
                        union(parent, first, i);
                    }
                }
            }
            // 组聚合：count 求和、组内关键词频次（按问题频次加权）定组名、样本 Top3
            Map<Integer, Integer> groupCount = new HashMap<>();
            Map<Integer, Map<String, Integer>> groupKeywords = new HashMap<>();
            Map<Integer, List<String>> groupSamples = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int root = find(parent, i);
                groupCount.merge(root, counts.get(i), Integer::sum);
                Map<String, Integer> kws = groupKeywords.computeIfAbsent(root, k -> new HashMap<>());
                for (String kw : keywordSets.get(i)) {
                    kws.merge(kw, counts.get(i), Integer::sum);
                }
                List<String> samples = groupSamples.computeIfAbsent(root, k -> new ArrayList<>());
                if (samples.size() < 3) {
                    samples.add(queries.get(i));
                }
            }
            List<Topic> topics = groupCount.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .limit(20)
                    .map(e -> {
                        int root = e.getKey();
                        String topic = groupKeywords.get(root).entrySet().stream()
                                .max(Map.Entry.comparingByValue())
                                .map(Map.Entry::getKey).orElse("其他");
                        return new Topic(topic, e.getValue(), groupSamples.get(root));
                    })
                    .toList();
            return new KbHealth((int) total, (int) unanswered, rate, topics);
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[知识库健康度] 统计失败，降级: {}", e.getMessage());
            return null;
        }
    }

    /** 停用词剥离后提取 2+ 字中文词（连续中文段按停用词切分） */
    private Set<String> extractKeywords(String query) {
        // 去 @提及、标点与空白
        String text = query.replaceAll("@_user_\\d+", "")
                .replaceAll("[\\p{P}\\p{S}\\s]+", " ");
        for (String sw : STOPWORDS) {
            text = text.replace(sw, " ");
        }
        Set<String> keywords = new HashSet<>();
        for (String seg : text.split("\\s+")) {
            if (seg.length() >= 2 && seg.matches("[\\u4e00-\\u9fa5]{2,}")) {
                keywords.add(seg);
            }
        }
        return keywords;
    }

    private static int find(int[] parent, int i) {
        while (parent[i] != i) {
            parent[i] = parent[parent[i]];
            i = parent[i];
        }
        return i;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    private static long num(Object o) {
        return o instanceof Number num ? num.longValue() : 0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
