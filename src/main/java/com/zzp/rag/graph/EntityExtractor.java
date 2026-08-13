package com.zzp.rag.graph;

import com.google.gson.Gson;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 实体关系抽取器。
 * 用 DeepSeek V3 (contextualChatModel, temperature 0.1) 从文本中抽取
 * 实体和关系，用于构建知识图谱。
 * <p>
 * 两类抽取：
 * 1. 摄入时：从 chunk 中抽取完整实体+关系 → 写入 Neo4j
 * 2. 查询时：从用户问题中抽取实体名称 → 图遍历入口
 */
@Slf4j
@Component
public class EntityExtractor {

    private final ChatModel chatModel;
    private final Gson gson = new Gson();

    public EntityExtractor(@Qualifier("graphChatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /**
     * 从 chunk 文本中抽取实体和关系（摄入时用）
     * @return 结构化图数据；抽取失败返回 empty
     */
    public GraphData extract(String text, String docId, int chunkIndex) {
        String prompt = buildExtractPrompt(text);
        // 首试 + 1 次重试：LLM JSON 输出偶发截断/畸形（MalformedJsonException）
        int maxAttempts = 2;
        String lastJson = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                String response = chatModel.chat(prompt);
                lastJson = cleanJson(response);
                GraphData data = gson.fromJson(lastJson, GraphData.class);
                if (data != null && data.entities() != null && !data.entities().isEmpty()) {
                    log.debug("[实体抽取] docId={} chunk={} entities={} relations={}",
                            docId, chunkIndex,
                            data.entities().size(),
                            data.relations() != null ? data.relations().size() : 0);
                    return data;
                }
                if (attempt < maxAttempts) {
                    log.info("[实体抽取] 空结果重试 {}/{} docId={} chunk={}",
                            attempt, maxAttempts, docId, chunkIndex);
                }
            } catch (Exception e) {
                if (attempt >= maxAttempts) {
                    log.warn("[实体抽取] 失败 docId={} chunk={}: {}", docId, chunkIndex, e.getMessage());
                    // 截断 JSON 抢救：正则捞回完整对象（qwen-turbo 偶发提前停止生成，丢弃太可惜）
                    return salvage(lastJson, docId, chunkIndex);
                }
                log.info("[实体抽取] 解析失败重试 {}/{} docId={} chunk={}: {}",
                        attempt, maxAttempts, docId, chunkIndex, e.getMessage());
            }
        }
        return GraphData.empty();
    }

    /** 截断 JSON 抢救：捞回完整的实体/关系对象（Gson 严格解析失败的兜底） */
    private GraphData salvage(String json, String docId, int chunkIndex) {
        if (json == null) return GraphData.empty();
        try {
            List<GraphData.Entity> entities = new ArrayList<>();
            Matcher em = ENTITY_SALVAGE.matcher(json);
            while (em.find()) {
                entities.add(new GraphData.Entity(em.group(1), em.group(2)));
            }
            List<GraphData.Relation> relations = new ArrayList<>();
            Matcher rm = RELATION_SALVAGE.matcher(json);
            while (rm.find()) {
                relations.add(new GraphData.Relation(rm.group(1), rm.group(2), rm.group(3)));
            }
            if (!entities.isEmpty()) {
                log.info("[实体抽取] 截断JSON抢救 docId={} chunk={} entities={} relations={}",
                        docId, chunkIndex, entities.size(), relations.size());
                return new GraphData(entities, relations);
            }
        } catch (Exception ignored) {
            // 抢救失败按空处理
        }
        return GraphData.empty();
    }

    /** 完整实体对象（含 name/type 两个字段） */
    private static final java.util.regex.Pattern ENTITY_SALVAGE = java.util.regex.Pattern.compile(
            "\\{\\s*\"name\"\\s*:\\s*\"([^\"]{1,100})\"\\s*,\\s*\"type\"\\s*:\\s*\"([^\"]{0,50})\"\\s*}");

    /** 完整关系对象（subject/relation/object 三字段） */
    private static final java.util.regex.Pattern RELATION_SALVAGE = java.util.regex.Pattern.compile(
            "\\{\\s*\"subject\"\\s*:\\s*\"([^\"]{1,100})\"\\s*,\\s*\"relation\"\\s*:\\s*\"([^\"]{1,50})\"\\s*,\\s*\"object\"\\s*:\\s*\"([^\"]{1,100})\"\\s*}");

    /**
     * 从用户查询中提取实体名称（检索时用，用于图遍历入口）
     */
    public List<String> extractEntityNames(String query) {
        String prompt = buildEntityNamesPrompt(query);
        try {
            String response = chatModel.chat(prompt);
            String json = cleanJson(response);
            @SuppressWarnings("unchecked")
            List<String> names = gson.fromJson(json, List.class);
            return names != null ? names : List.of();
        } catch (Exception e) {
            log.debug("[实体名称提取] 失败 query={}: {}", query.substring(0, Math.min(30, query.length())), e.getMessage());
            return List.of();
        }
    }

    private String buildExtractPrompt(String text) {
        return """
            你是实体关系抽取专家。从以下文本中提取所有实体和它们之间的关系。

            文本：
            %s

            只返回JSON（不要markdown代码块，不要任何解释性文字，不要省略字段），格式：
            {"entities":[{"name":"实体名","type":"类型"}],"relations":[{"subject":"主体","relation":"关系","object":"客体"}]}

            实体类型示例：人物、地点、时间、组织、概念、事件、作品、法规
            """.formatted(text);
    }

    private String buildEntityNamesPrompt(String query) {
        return """
            从以下问题中提取关键实体名称。只返回JSON字符串数组，不要其他内容。
            问题：%s
            格式：["实体1", "实体2"]
            """.formatted(query);
    }

    /** 剥离 markdown 代码块标记 */
    static String cleanJson(String response) {
        if (response == null) return "{}";
        String cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```\\w*\\s*", "");
            cleaned = cleaned.replaceFirst("\\s*```$", "");
        }
        return cleaned;
    }

    // ==================== 数据模型 ====================

    public record GraphData(List<Entity> entities, List<Relation> relations) {
        public static GraphData empty() {
            return new GraphData(List.of(), List.of());
        }

        public record Entity(String name, String type) {}
        public record Relation(String subject, String relation, String object) {}
    }
}
