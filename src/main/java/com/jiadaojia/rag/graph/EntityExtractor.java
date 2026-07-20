package com.jiadaojia.rag.graph;

import com.google.gson.Gson;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

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
        try {
            String response = chatModel.chat(prompt);
            String json = cleanJson(response);
            GraphData data = gson.fromJson(json, GraphData.class);
            if (data != null && data.entities() != null && !data.entities().isEmpty()) {
                log.debug("[实体抽取] docId={} chunk={} entities={} relations={}",
                        docId, chunkIndex,
                        data.entities().size(),
                        data.relations() != null ? data.relations().size() : 0);
                return data;
            }
        } catch (Exception e) {
            log.warn("[实体抽取] 失败 docId={} chunk={}: {}", docId, chunkIndex, e.getMessage());
        }
        return GraphData.empty();
    }

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

            只返回JSON（不要markdown代码块），格式：
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
