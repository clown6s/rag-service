package com.zzp.rag.web;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.config.MilvusConfig;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.FeedbackAdoptEntity;
import com.zzp.rag.mysql.mapper.FeedbackAdoptMapper;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.model.embedding.EmbeddingModel;
import io.milvus.v2.client.MilvusClientV2;
import io.milvus.v2.service.vector.request.InsertReq;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * 上下文飞轮 API（人工审核制：用户认可的回答 → 采纳 → Q&A chunk 回流知识库）。
 * <p>
 * POST /api/rag/admin/feedback/adopt {query, answer, source}
 * - 防重复：query SHA-256 唯一（feedback_adopt 表）
 * - chunk 格式与 Q&A 模板一致（"问题：Q\n回答：\nA"），检索/回答链路零适配
 * - document_id="flywheel"、title="人工采纳问答"，知识库列表可见、可重命名、可删除
 * - 软依赖：MySQL 不可用拒绝采纳（防重复校验必需）；Milvus 失败记日志返回错误
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin/feedback")
public class FeedbackController {

    public static final String FLYWHEEL_DOC_ID = "flywheel";
    public static final String FLYWHEEL_TITLE = "人工采纳问答";

    private final FeedbackAdoptMapper mapper;
    private final MysqlStatus status;
    private final MilvusClientV2 milvusClient;
    private final EmbeddingModel embeddingModel;

    @Value("${milvus.collection}")
    private String collectionName;

    public FeedbackController(FeedbackAdoptMapper mapper, MysqlStatus status,
                              MilvusClientV2 milvusClient, EmbeddingModel embeddingModel) {
        this.mapper = mapper;
        this.status = status;
        this.milvusClient = milvusClient;
        this.embeddingModel = embeddingModel;
    }

    public record AdoptRequest(String query, String answer, String source) {}
    public record AdoptResult(long adoptId, String documentId, String title) {}

    @PostMapping("/adopt")
    public ApiResponse<AdoptResult> adopt(@RequestBody AdoptRequest req) {
        if (req.query() == null || req.query().isBlank()
                || req.answer() == null || req.answer().isBlank()) {
            return ApiResponse.fail("问题和答案不能为空");
        }
        String query = req.query().strip();
        String answer = req.answer().strip();
        if (answer.length() > 8000) {
            return ApiResponse.fail("答案过长（>8000 字），不建议整体采纳");
        }
        if (!status.isAvailable()) {
            return ApiResponse.fail("业务库不可用，无法采纳");
        }
        String hash = sha256(query);
        try {
            // 1. 防重复（同问题只采纳一次）
            Long dup = mapper.selectCount(
                    new QueryWrapper<FeedbackAdoptEntity>().eq("query_hash", hash));
            if (dup != null && dup > 0) {
                return ApiResponse.fail("该问题已采纳过，无需重复");
            }
            // 2. Q&A chunk（与 QaTemplate 格式一致）
            String chunkText = "问题：" + query + "\n回答：\n" + answer;
            Embedding embedding = embeddingModel.embed(chunkText).content();
            JsonObject row = new JsonObject();
            row.addProperty(MilvusConfig.FIELD_TEXT, chunkText);
            row.add(MilvusConfig.FIELD_DENSE,
                    new com.google.gson.Gson().toJsonTree(embedding.vector()));
            row.addProperty(MilvusConfig.FIELD_DOCUMENT_ID, FLYWHEEL_DOC_ID);
            row.addProperty(MilvusConfig.FIELD_TITLE, FLYWHEEL_TITLE);
            row.addProperty(MilvusConfig.FIELD_SOURCE,
                    req.source() != null && !req.source().isBlank()
                            ? req.source() : "人工采纳");
            milvusClient.insert(InsertReq.builder()
                    .collectionName(collectionName)
                    .data(List.of(row))
                    .build());
            // 3. 防重复记录（唯一键兜底并发）
            FeedbackAdoptEntity e = new FeedbackAdoptEntity();
            e.setQueryHash(hash);
            e.setQuery(query);
            e.setAnswer(answer);
            e.setSource(req.source());
            e.setAdoptedBy(MDC.get(AuditEvent.MDC_USER_KEY));
            mapper.insert(e);
            log.info("[飞轮] 采纳问答入库: {}（by {}）", query.substring(0, Math.min(40, query.length())),
                    e.getAdoptedBy());
            return ApiResponse.ok(new AdoptResult(e.getId(), FLYWHEEL_DOC_ID, FLYWHEEL_TITLE));
        } catch (Exception ex) {
            log.error("[飞轮] 采纳失败", ex);
            return ApiResponse.fail("采纳失败：" + ex.getMessage());
        }
    }

    private String sha256(String s) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
