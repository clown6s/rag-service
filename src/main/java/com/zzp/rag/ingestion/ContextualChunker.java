package com.zzp.rag.ingestion;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 上下文增强切分器 — Contextual Retrieval 核心实现
 * <p>
 * 流程：
 * 1. LLM 理解整篇文档 → 生成简要摘要
 * 2. 摘要拼到每个 chunk 前面作为前置上下文
 * 3. 递归切分器切割
 * <p>
 * 效果：Anthropic 实验表明检索失败率降低 49%
 */
@Slf4j
@Component
public class ContextualChunker {

    private final ChatModel chatModel;
    private final int maxSegmentSize;
    private final int overlapSize;

    public ContextualChunker(
            @Qualifier("contextualChatModel") ChatModel chatModel,
            @Value("${rag.chunking.max-segment-size}") int maxSegmentSize,
            @Value("${rag.chunking.overlap-size}") int overlapSize) {
        this.chatModel = chatModel;
        this.maxSegmentSize = maxSegmentSize;
        this.overlapSize = overlapSize;
    }

    /**
     * 上下文增强 + 切分
     * <p>
     * 解析层提供标题结构（sections≥2 且有内容）时按章节切分：
     * 标题作为强边界，章节名注入每个 chunk 正文（检索与 LLM 都能感知 chunk 归属）；
     * 否则走原有路径（摘要前置 + 递归句子切分，含法律条款专用切分）。
     */
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName) {
        String rawText = parsed.text() == null ? "" : parsed.text();
        if (fileName == null) fileName = "unknown";

        // Step 1: LLM 生成文档级摘要
        String docSummary = generateSummary(rawText, fileName);

        // Step 2: 选择切分策略
        List<TextSegment> segments;
        List<ParsedDocument.ParsedSection> sections = parsed.sections();
        boolean sectionAware = sections != null && sections.size() >= 2 && sections.stream()
                .anyMatch(s -> s.content() != null && !s.content().isBlank());
        if (sectionAware) {
            segments = chunkBySections(sections, fileName, docSummary);
        } else {
            String enrichedText = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n" + rawText;
            segments = splitBySentence(enrichedText);
        }

        // 过滤空白 segment（RecursiveSentenceSplitter 可能产生纯空白片段，导致向量化报错）
        segments = segments.stream()
                .filter(s -> s.text() != null && !s.text().isBlank())
                .toList();

        // Step 3: 注入元数据
        fillMetadata(segments, fileName, docSummary);

        log.info("[切分] 文件={} chunks={} 按章节={} 摘要={}...",
                fileName, segments.size(), sectionAware,
                docSummary.length() > 80 ? docSummary.substring(0, 80) : docSummary);
        return segments;
    }

    /**
     * 按标题章节切分：标题作为强边界；章节名注入每个 chunk 正文前缀
     * （上下文增强：向量化/BM25 检索与 LLM 回答都能感知 chunk 的章节归属）
     */
    private List<TextSegment> chunkBySections(
            List<ParsedDocument.ParsedSection> sections, String fileName, String docSummary) {
        List<TextSegment> all = new ArrayList<>();
        for (ParsedDocument.ParsedSection section : sections) {
            String title = section.title();
            String sectionLabel = (title == null || title.isBlank()) ? "开头部分" : title;
            String prefix = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n"
                    + "[章节: " + sectionLabel + "]\n\n";
            String content = section.content() == null ? "" : section.content();
            List<TextSegment> segs =
                    new RecursiveSentenceSplitter(maxSegmentSize, overlapSize)
                            .split(Document.from(prefix + content));
            for (TextSegment seg : segs) {
                seg.metadata().put("section", sectionLabel);
            }
            all.addAll(segs);
        }
        return all;
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
     * 按句子递归切分
     * 策略：按句号/换行优先切，落到 256~512 字之间
     */
    private List<TextSegment> splitBySentence(String text) {
        // 预处理：合并多个换行
        text = text.replaceAll("\\n{3,}", "\n\n");

        // 检测法律条款模式"第X条"——至少5条才按条款切分（避免Q&A文档误触发）
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("第[一二三四五六七八九十百千零\\d]+条");
        java.util.regex.Matcher m = p.matcher(text);
        int articleCount = 0;
        while (m.find()) articleCount++;
        if (articleCount >= 5) {
            return splitByLegalArticle(text);
        }

        Document doc = Document.from(text);
        return new RecursiveSentenceSplitter(maxSegmentSize, overlapSize).split(doc);
    }

    /**
     * 按法律条款"第X条"边界切分，每条独立 chunk
     * 解决 BM25 分词"第五条"→"第五"+"条"误匹配"第五十四条"的问题
     * 关键修复：第一个"第X条"之前的内容（如文档标题、景点知识等）也要保留为 chunk
     */
    private List<TextSegment> splitByLegalArticle(String text) {
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("第[一二三四五六七八九十百千零\\d]+条");
        java.util.regex.Matcher m = p.matcher(text);
        java.util.List<Integer> positions = new java.util.ArrayList<>();
        while (m.find()) {
            positions.add(m.start());
        }
        // 不足2条边界，回退常规切分
        if (positions.size() < 2) {
            Document doc = Document.from(text);
            return new RecursiveSentenceSplitter(maxSegmentSize, overlapSize).split(doc);
        }

        java.util.List<TextSegment> segments = new java.util.ArrayList<>();

        // 关键修复：第一个"第X条"之前的内容也要保留为 chunk（可能是景点知识、文档标题等）
        String preArticleText = text.substring(0, positions.get(0)).trim();
        if (!preArticleText.isEmpty()) {
            Document preDoc = Document.from(preArticleText);
            segments.addAll(new RecursiveSentenceSplitter(maxSegmentSize, overlapSize).split(preDoc));
        }

        // 按"第X条"边界切分
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String chunk = text.substring(start, end).trim();
            if (chunk.isEmpty()) continue;
            // 单条超长再递归切
            if (chunk.length() > maxSegmentSize) {
                Document subDoc = Document.from(chunk);
                segments.addAll(new RecursiveSentenceSplitter(maxSegmentSize, overlapSize).split(subDoc));
            } else {
                segments.add(TextSegment.from(chunk));
            }
        }
        return segments;
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
            if (text == null || text.isBlank()) {
                return List.of();
            }
            if (text.length() <= maxSize) {
                return List.of(TextSegment.from(text, meta.copy()));
            }

            // 已无更细的分隔符，硬切
            if (depth >= seps.length) {
                return hardSplit(text, meta);
            }

            String[] parts = text.split(seps[depth], -1);
            java.util.List<TextSegment> result = new java.util.ArrayList<>();
            StringBuilder current = new StringBuilder();

            for (String part : parts) {
                // 跳过空白 part
                if (part == null || part.isBlank()) continue;

                // 单个 part 超长 → 递归用下一层分隔符切
                if (part.length() > maxSize) {
                    // 先把 current 落盘
                    if (!current.toString().isBlank()) {
                        result.add(TextSegment.from(current.toString(), meta.copy()));
                        String saved = current.toString();
                        int start = Math.max(0, saved.length() - overlap);
                        current = new StringBuilder(saved.substring(start));
                    }
                    // 递归切超长 part
                    result.addAll(splitRecursive(part, seps, depth + 1, meta));
                    current.setLength(0);
                    continue;
                }

                String candidate = current.isEmpty() ? part : current + seps[depth] + part;

                if (candidate.length() > maxSize && !current.isEmpty()) {
                    result.add(TextSegment.from(current.toString(), meta.copy()));
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

            if (!current.toString().isBlank()) {
                result.add(TextSegment.from(current.toString(), meta.copy()));
            }

            return result;
        }

        /** 兜底硬切：按 maxSize 强制截断 */
        private List<TextSegment> hardSplit(String text, Metadata meta) {
            java.util.List<TextSegment> result = new java.util.ArrayList<>();
            for (int i = 0; i < text.length(); i += maxSize - overlap) {
                int end = Math.min(i + maxSize, text.length());
                String chunk = text.substring(i, end);
                if (!chunk.isBlank()) {
                    result.add(TextSegment.from(chunk, meta.copy()));
                }
            }
            return result;
        }
    }
}
