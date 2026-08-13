package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;

import java.util.List;

/**
 * 切分模板注册表 — 按文档特征路由到首个匹配模板
 * <p>
 * 顺序即优先级：Sections（有结构）→ Laws（条款）→ QA（问答对）→ General（兜底）。
 * 新增文档类型 = 注册一个新模板类，主流程零改动。
 */
public class TemplateRegistry {

    private final List<ChunkTemplate> templates;

    public TemplateRegistry(List<ChunkTemplate> templates) {
        this.templates = templates;
        // 防御：最后一个模板必须 always-match（兜底）
        if (templates.isEmpty() || !templates.get(templates.size() - 1)
                .matches(ParsedDocumentStub.EMPTY)) {
            throw new IllegalArgumentException("模板注册表最后一个必须是兜底模板");
        }
    }

    /** 选择首个匹配模板 */
    public ChunkTemplate select(ParsedDocument parsed) {
        for (ChunkTemplate template : templates) {
            if (template.matches(parsed)) {
                return template;
            }
        }
        // 防御性兜底（正常不会走到）
        return templates.get(templates.size() - 1);
    }

    public List<ChunkTemplate> all() {
        return templates;
    }

    /** 空文档桩，用于启动时校验兜底模板 */
    private static class ParsedDocumentStub {
        static final ParsedDocument EMPTY = new ParsedDocument("", List.of(), List.of());
    }
}
