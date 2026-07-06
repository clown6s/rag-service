package com.jiadaojia.rag.retrieval;

import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Query 改写器
 *
 * 策略1: Multi-Query — 生成多个角度查询，提高召回覆盖率
 * 策略2: 关键词提取 — 用于 BM25 精确匹配
 */
@Slf4j
@Component
public class QueryRewriter {

    private final RewriterService rewriter;

    public QueryRewriter(@Qualifier("chatModel") ChatLanguageModel chatModel) {
        this.rewriter = AiServices.builder(RewriterService.class)
                .chatLanguageModel(chatModel)
                .build();
    }

    public record RewriteResult(List<String> queries, List<String> keywords) {
        public static RewriteResult fallback(String query) {
            return new RewriteResult(List.of(query), List.of());
        }
    }

    /**
     * 改写用户查询
     */
    public RewriteResult rewrite(String originalQuery) {
        try {
            return rewriter.rewrite(originalQuery);
        } catch (Exception e) {
            log.warn("[改写] LLM调用失败，使用原始查询: {}", e.getMessage());
            return RewriteResult.fallback(originalQuery);
        }
    }

    /**
     * LangChain4j AiServices 接口
     * 自动处理 prompt 模板、JSON 解析
     */
    interface RewriterService {

        @SystemMessage("""
                你是查询改写专家。任务：将用户问题改写为 3 个不同角度的检索查询。
                同时提取 3-5 个核心关键词用于精确匹配。

                返回格式（必须是合法 JSON）：
                {"queries": ["改写查询1", "改写查询2", "改写查询3"], "keywords": ["关键词1", "关键词2", "关键词3"]}

                改写原则：
                - 不同角度：同义词替换、问题拆分、视角转换
                - 关键词：名词短语、专有名词、数字编号
                - 保留原始语义，不添加额外信息
                """)
        @UserMessage("改写查询：{{it}}")
        RewriteResult rewrite(@V("it") String query);
    }
}
