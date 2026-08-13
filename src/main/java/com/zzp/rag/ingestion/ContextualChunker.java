package com.zzp.rag.ingestion;

import com.zzp.rag.ingestion.chunking.ChunkTemplate;
import com.zzp.rag.ingestion.chunking.GeneralTemplate;
import com.zzp.rag.ingestion.chunking.LawsTemplate;
import com.zzp.rag.ingestion.chunking.QaTemplate;
import com.zzp.rag.ingestion.chunking.SectionsTemplate;
import com.zzp.rag.ingestion.chunking.TemplateRegistry;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 上下文增强切分器 — Contextual Retrieval + 模板注册体系
 * <p>
 * 流程：
 * 1. LLM 理解整篇文档 → 生成简要摘要（Contextual Retrieval，检索失败率降低 ~49%）
 * 2. 模板注册表按文档特征路由切分策略：
 *    Sections（标题结构）→ Laws（第X条）→ QA（问答对）→ General（递归句子）
 * 3. 所有模板共享摘要前置 + 元数据注入
 * <p>
 * 模板体系思路参考 RAGFlow 的 chunk_method 设计（Apache License 2.0）
 */
@Slf4j
@Component
public class ContextualChunker {

    private final ChatModel chatModel;
    private final TemplateRegistry registry;

    public ContextualChunker(
            @Qualifier("contextualChatModel") ChatModel chatModel,
            @Value("${rag.chunking.max-segment-size}") int maxSegmentSize,
            @Value("${rag.chunking.overlap-size}") int overlapSize) {
        this.chatModel = chatModel;
        // 注册表顺序即优先级，最后一位必须是兜底模板（GeneralTemplate always-match）
        this.registry = new TemplateRegistry(List.of(
                new SectionsTemplate(maxSegmentSize, overlapSize),
                new LawsTemplate(maxSegmentSize, overlapSize),
                new QaTemplate(maxSegmentSize, overlapSize),
                new GeneralTemplate(maxSegmentSize, overlapSize)));
    }

    /**
     * 上下文增强 + 模板路由 + 切分
     */
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName) {
        String rawText = parsed.text() == null ? "" : parsed.text();
        if (fileName == null) {
            fileName = "unknown";
        }

        // Step 1: LLM 生成文档级摘要
        String docSummary = generateSummary(rawText, fileName);

        // Step 2: 模板路由切分
        ChunkTemplate template = registry.select(parsed);
        List<TextSegment> segments = template.chunk(parsed, fileName, docSummary);

        // 过滤空白 segment（切分器可能产生纯空白片段，导致向量化报错）
        segments = segments.stream()
                .filter(s -> s.text() != null && !s.text().isBlank())
                .toList();

        // Step 3: 注入元数据
        fillMetadata(segments, fileName, docSummary);

        log.info("[切分] 文件={} 模板={} chunks={} 摘要={}...",
                fileName, template.name(), segments.size(),
                docSummary.length() > 80 ? docSummary.substring(0, 80) : docSummary);
        return segments;
    }

    private void fillMetadata(List<TextSegment> segments, String fileName, String docSummary) {
        String now = LocalDateTime.now().toString();
        for (int i = 0; i < segments.size(); i++) {
            TextSegment seg = segments.get(i);
            seg.metadata().put("source", fileName);
            seg.metadata().put("chunk_index", i);
            seg.metadata().put("total_chunks", segments.size());
            seg.metadata().put("created_at", now);
            seg.metadata().put("doc_summary", docSummary);
        }
    }

    /**
     * 用 LLM 生成文档摘要（轻量模型，成本极低）
     * 失败时返回默认摘要，不阻断摄入流程
     */
    private String generateSummary(String rawText, String fileName) {
        // 取文档前段（3000字以内）给 LLM，省 token
        String preview = rawText.length() > 3000
                ? rawText.substring(0, 3000)
                : rawText;

        String prompt = """
                你是一个文档摘要生成器。阅读以下文档内容，用一句话（不超过50字）描述文档的主题和核心内容。
                不要添加任何前缀或解释。

                文件名: %s
                内容:
                %s
                """.formatted(fileName, preview);

        try {
            String result = chatModel.chat(prompt);
            if (result != null && !result.isBlank()) {
                return result.trim();
            }
        } catch (Exception e) {
            log.warn("[切分] LLM摘要生成失败，使用默认摘要: {}", e.getMessage());
        }
        return "文档: " + fileName;
    }
}
