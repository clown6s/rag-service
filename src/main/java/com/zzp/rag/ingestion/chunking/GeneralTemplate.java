package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 通用兜底切分模板 — 摘要前置 + 递归句子切分
 * 始终 matches（注册表最后一位），保证任何文档都有切分策略
 */
public class GeneralTemplate implements ChunkTemplate {

    private final SentenceSplitter splitter;

    public GeneralTemplate(int maxSize, int overlap) {
        this.splitter = new SentenceSplitter(maxSize, overlap);
    }

    @Override
    public String name() {
        return "general";
    }

    @Override
    public boolean matches(ParsedDocument parsed) {
        return true;   // 兜底模板
    }

    @Override
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary) {
        String enrichedText = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n"
                + (parsed.text() == null ? "" : parsed.text());
        return splitter.split(Document.from(enrichedText));
    }
}
