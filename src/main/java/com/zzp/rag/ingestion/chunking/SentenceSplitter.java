package com.zzp.rag.ingestion.chunking;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 递归句子切分器（从 ContextualChunker 内嵌实现抽出，供 General/Laws/QA 模板共用）
 * 策略：按段落/换行/句号/标点层级递归切分，落到 maxSize 以内，带 overlap 重叠
 */
class SentenceSplitter {

    private final int maxSize;
    private final int overlap;

    SentenceSplitter(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.overlap = overlap;
    }

    List<TextSegment> split(Document doc) {
        // 按句号、问号、感叹号、换行作为分隔符递归切分
        String[] separators = {"\n\n", "\n", "。", "！", "？", "；", "，", " "};
        return splitRecursive(doc.text(), separators, 0, doc.metadata());
    }

    private List<TextSegment> splitRecursive(String text, String[] seps, int depth,
                                             dev.langchain4j.data.document.Metadata meta) {
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
        List<TextSegment> result = new ArrayList<>();
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
    private List<TextSegment> hardSplit(String text, dev.langchain4j.data.document.Metadata meta) {
        List<TextSegment> result = new ArrayList<>();
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
