package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法律条款切分模板 — 按"第X条"边界切分，每条（或条款组）独立 chunk
 * <p>
 * 从 ContextualChunker 原内嵌逻辑迁出（原注释见 git 历史）：
 * 解决 BM25 分词"第五条"→"第五"+"条"误匹配"第五十四条"的问题；
 * 第一个"第X条"之前的内容（标题、序言等）保留为独立 chunk。
 */
public class LawsTemplate implements ChunkTemplate {

    /** 至少 N 条"第X条"才按条款切分（避免 Q&A 文档等误触发） */
    private static final int MIN_ARTICLES = 5;

    private static final Pattern ARTICLE = Pattern.compile("第[一二三四五六七八九十百千零\\d]+条");

    private final int maxSize;
    private final SentenceSplitter splitter;

    public LawsTemplate(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.splitter = new SentenceSplitter(maxSize, overlap);
    }

    @Override
    public String name() {
        return "laws";
    }

    @Override
    public boolean matches(ParsedDocument parsed) {
        int count = 0;
        Matcher m = ARTICLE.matcher(parsed.text());
        while (m.find()) {
            count++;
        }
        return count >= MIN_ARTICLES;
    }

    @Override
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary) {
        String text = parsed.text();
        String prefix = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n";

        Matcher m = ARTICLE.matcher(text);
        List<Integer> positions = new ArrayList<>();
        while (m.find()) {
            positions.add(m.start());
        }
        // 不足 2 条边界，回退通用切分（防御，matches 已保证 ≥5）
        if (positions.size() < 2) {
            return splitter.split(Document.from(prefix + text));
        }

        List<TextSegment> segments = new ArrayList<>();

        // 第一个"第X条"之前的内容（文档标题、序言等）保留为 chunk
        String preArticleText = text.substring(0, positions.get(0)).trim();
        if (!preArticleText.isEmpty()) {
            segments.addAll(splitter.split(Document.from(prefix + preArticleText)));
        }

        // 按"第X条"边界切分
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String chunk = text.substring(start, end).trim();
            if (chunk.isEmpty()) {
                continue;
            }
            // 单条超长再递归切
            if (chunk.length() > maxSize) {
                segments.addAll(splitter.split(Document.from(prefix + chunk)));
            } else {
                segments.add(TextSegment.from(prefix + chunk));
            }
        }
        return segments;
    }
}
