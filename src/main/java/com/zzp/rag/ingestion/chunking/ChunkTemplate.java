package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.segment.TextSegment;

import java.util.List;

/**
 * 切分模板 — 按文档类型选择切分策略
 * <p>
 * 思路参考 RAGFlow 的 chunk_method 模板体系（rag/app/{laws,manual,qa,naive,...}.py，
 * Apache License 2.0）；本实现为其 Java 精简版：检测→切分→元数据，注册式扩展，
 * 新增文档类型 = 新增一个模板类，主流程零改动。
 */
public interface ChunkTemplate {

    /** 模板名（日志/观测用） */
    String name();

    /** 该文档是否适用本模板（基于解析产物的特征检测） */
    boolean matches(ParsedDocument parsed);

    /**
     * 切分
     * @param docSummary 文档级 LLM 摘要（由调用方生成一次，模板负责注入上下文前缀）
     */
    List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary);
}
