package com.zzp.rag.web;

import com.zzp.rag.retrieval.RetrievalPipeline;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 检索调试 API — 绕过生成直接看检索结果
 * <p>
 * 用途：评测测试集构建（contexts 回放）、检索参数调试、未来技能/数字员工的检索能力出口。
 * 与 /api/rag/agent/chat 区别：不调 LLM 生成，只跑检索链路（改写→召回→重排→图遍历）。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag")
public class SearchController {

    private final RetrievalPipeline retrievalPipeline;

    public SearchController(RetrievalPipeline retrievalPipeline) {
        this.retrievalPipeline = retrievalPipeline;
    }

    @PostMapping("/search")
    public ApiResponse<SearchResponse> search(@Valid @RequestBody SearchRequest req) {
        var result = retrievalPipeline.execute(req.query(), null);
        List<SearchHit> hits = result.segments().stream()
                .map(seg -> {
                    Object score = seg.metadata().toMap().get("rerank_score");
                    return new SearchHit(
                            seg.text(),
                            seg.metadata().getString("source"),
                            seg.metadata().getString("section"),
                            score instanceof Number ? ((Number) score).doubleValue() : null);
                })
                .toList();
        return ApiResponse.ok(new SearchResponse(
                hits, result.elapsedMs(), result.rerankDegraded(), result.topScore()));
    }

    // ==================== 请求/响应 record ====================

    public record SearchRequest(@NotBlank String query) {}

    public record SearchHit(String text, String source, String section, Double score) {}

    public record SearchResponse(
            List<SearchHit> hits,
            long elapsedMs,
            boolean rerankDegraded,
            Double topScore) {}
}
