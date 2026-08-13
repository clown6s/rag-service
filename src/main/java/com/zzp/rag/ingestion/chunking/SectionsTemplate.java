package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 标题章节切分模板 — 依赖解析层产出的 sections 结构（ai-sidecar docx 标题层级提取）
 * <p>
 * 标题作为强边界；章节名注入每个 chunk 正文前缀
 * （上下文增强：向量化/BM25 检索与 LLM 回答都能感知 chunk 的章节归属）
 */
public class SectionsTemplate implements ChunkTemplate {

    private final SentenceSplitter splitter;

    public SectionsTemplate(int maxSize, int overlap) {
        this.splitter = new SentenceSplitter(maxSize, overlap);
    }

    @Override
    public String name() {
        return "sections";
    }

    @Override
    public boolean matches(ParsedDocument parsed) {
        List<ParsedDocument.ParsedSection> sections = parsed.sections();
        return sections != null && sections.size() >= 2 && sections.stream()
                .anyMatch(s -> s.content() != null && !s.content().isBlank());
    }

    @Override
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary) {
        List<TextSegment> all = new ArrayList<>();
        for (ParsedDocument.ParsedSection section : parsed.sections()) {
            String title = section.title();
            String sectionLabel = (title == null || title.isBlank()) ? "开头部分" : title;
            String prefix = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n"
                    + "[章节: " + sectionLabel + "]\n\n";
            String content = section.content() == null ? "" : section.content();
            List<TextSegment> segs = splitter.split(Document.from(prefix + content));
            for (TextSegment seg : segs) {
                seg.metadata().put("section", sectionLabel);
            }
            all.addAll(segs);
        }
        return all;
    }
}
