package com.zzp.rag.ingestion;

import java.util.List;

/**
 * 解析产物 — 纯文本 + 结构化信息
 * <p>
 * sections：标题层级结构（当前由 ai-sidecar DeepDoc 布局识别就绪后填充，
 * 届时 ContextualChunker 用它做标题边界切分 + section 元数据）
 * warnings：解析告警（如"疑似扫描件，需 OCR"），摄入时记日志供排查
 */
public record ParsedDocument(
        String text,
        List<String> warnings,
        List<ParsedSection> sections) {

    /** 文档章节（level: 标题层级 1-3，content: 该章节文本） */
    public record ParsedSection(int level, String title, String content) {}
}
