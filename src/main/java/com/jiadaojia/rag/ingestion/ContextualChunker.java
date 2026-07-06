package com.jiadaojia.rag.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.input.Prompt;
import dev.langchain4j.model.input.PromptTemplate;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 上下文增强切分器 — Contextual Retrieval 核心实现
 *
 * 流程：
 * 1. LLM 理解整篇文档 → 生成简要摘要
 * 2. 摘要拼到每个 chunk 前面作为前置上下文
 * 3. 递归切分器切割
 *
 * 效果：Anthropic 实验表明检索失败率降低 49%
 */
@Slf4j
@Component
public class ContextualChunker {

    private final ChatLanguageModel chatModel;
    private final int maxSegmentSize;
    private final int overlapSize;

    public ContextualChunker(
            @Qualifier("contextualChatModel") ChatLanguageModel chatModel,
            @Value("${rag.chunking.max-segment-size}") int maxSegmentSize,
            @Value("${rag.chunking.overlap-size}") int overlapSize) {
        this.chatModel = chatModel;
        this.maxSegmentSize = maxSegmentSize;
        this.overlapSize = overlapSize;
    }

    /**
     * 上下文增强 + 切分
     */
    public List<TextSegment> chunk(String rawText, String fileName) {
        // Step 1: LLM 生成文档级摘要
        String docSummary = generateSummary(rawText, fileName);

        // Step 2: 拼接前置上下文
        String enrichedText = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n" + rawText;

        // Step 3: 递归切分（按句子边界，保持语义完整）
        List<TextSegment> segments = splitBySentence(enrichedText);

        // Step 4: 注入元数据
        String now = LocalDateTime.now().toString();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            seg.metadata().put("source", fileName);
            seg.metadata().put("chunk_index", i);
            seg.metadata().put("total_chunks", segments.size());
            seg.metadata().put("created_at", now);
            seg.metadata().put("doc_summary", docSummary);
        }

        log.info("[切分] 文件={} chunks={} 摘要={}...",
                fileName, segments.size(),
                docSummary.length() > 80 ? docSummary.substring(0, 80) : docSummary);
        return segments;
    }

    /**
     * 按句子递归切分
     * 策略：按句号/换行优先切，落到 256~512 字之间
     */
    private List<TextSegment> splitBySentence(String text) {
        // 预处理：合并多个换行
        text = text.replaceAll("\\n{3,}", "\n\n");

        Document doc = Document.from(text);
        // 递归切分：最大512字，overlap 50字
        return new RecursiveSentenceSplitter(maxSegmentSize, overlapSize).split(doc);
    }

    /**
     * 用 LLM 生成文档摘要（轻量模型，成本极低）
     */
    private String generateSummary(String rawText, String fileName) {
        // 取文档前段（3000字以内）给 LLM，省 token
        String preview = rawText.length() > 3000
                ? rawText.substring(0, 3000)
                : rawText;

        Prompt prompt = PromptTemplate.from("""
                你是一个文档摘要生成器。阅读以下文档内容，用一句话（不超过50字）描述文档的主题和核心内容。
                不要添加任何前缀或解释。

                文件名: {{fileName}}
                内容:
                {{content}}
                """)
                .apply(Map.of("fileName", fileName, "content", preview));

        return chatModel.generate(prompt.text()).trim();
    }

    /**
     * 内置递归句子切分器
     */
    private static class RecursiveSentenceSplitter {
        private final int maxSize;
        private final int overlap;

        RecursiveSentenceSplitter(int maxSize, int overlap) {
            this.maxSize = maxSize;
            this.overlap = overlap;
        }

        List<TextSegment> split(Document doc) {
            // 按句号、问号、感叹号、换行作为分隔符递归切分
            String[] separators = {"\n\n", "\n", "。", "！", "？", "；", "，", " "};
            return splitRecursive(doc.text(), separators, 0, doc.metadata());
        }

        private List<TextSegment> splitRecursive(String text, String[] seps, int depth, Metadata meta) {
            if (text.length() <= maxSize || depth >= seps.length) {
                return List.of(TextSegment.from(text, meta.copy()));
            }

            String[] parts = text.split(seps[depth], -1);
            java.util.List<TextSegment> result = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();

            for (String part : parts) {
                String candidate = current.isEmpty() ? part : current + seps[depth] + part;

                if (candidate.length() > maxSize && !current.isEmpty()) {
                    result.add(TextSegment.from(current.toString(), meta.copy()));
                    // overlap: 保留最后 overlap 个字符
                    String saved = current.toString();
                    int start = Math.max(0, saved.length() - overlap);
                    current = new StringBuilder(saved.substring(start));
                    current.append(seps[depth]).append(part);
                } else {
                    if (current.isEmpty()) {
                        current = new StringBuilder(part);
                    } else {
                        current.append(seps[depth]).append(part);
                    }
                }
            }

            if (!current.isEmpty()) {
                result.add(TextSegment.from(current.toString(), meta.copy()));
            }

            return result;
        }
    }
}
