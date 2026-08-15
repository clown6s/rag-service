package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.AnswerReviewEntity;
import com.zzp.rag.mysql.mapper.AnswerReviewMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * 答案审核流：高风险问题（合规敏感词命中）答案不直接下发，转入人工审核。
 * 软依赖：业务库不可用时不拦截（审核缺失优于服务不可用）。
 */
@Slf4j
@Service
public class ReviewService {

    public record ReviewView(Long id, String query, String answer, String sources,
                             Long employeeId, String user, String status,
                             String reviewer, String createdAt, String reviewedAt) {}

    /** 风险词（合规高风险问法；可配置覆盖） */
    @Value("${rag.review.risk-keywords:赔偿,辞退,裁员,竞业,劳动仲裁,工资争议,保密协议,诉讼,合同纠纷,经济补偿}")
    private String riskKeywords;

    @Value("${rag.review.enabled:true}")
    private boolean enabled;

    private final AnswerReviewMapper mapper;
    private final MysqlStatus status;

    public ReviewService(AnswerReviewMapper mapper, MysqlStatus status) {
        this.mapper = mapper;
        this.status = status;
    }

    /** 命中风险词（启用且业务库可用时生效） */
    public boolean isRisky(String query) {
        if (!enabled || query == null || query.isBlank()) {
            return false;
        }
        return Arrays.stream(riskKeywords.split("[,，]"))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .anyMatch(query::contains);
    }

    /** 建审核单（PENDING）；业务库不可用返回 false（不拦截） */
    public boolean submit(String query, String answer, List<String> sources,
                          Long employeeId, String user) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            AnswerReviewEntity e = new AnswerReviewEntity();
            e.setQuery(query);
            e.setAnswer(answer);
            e.setSources(sources == null || sources.isEmpty() ? "" : String.join("、", sources));
            e.setEmployeeId(employeeId);
            e.setUser(user);
            e.setStatus("PENDING");
            mapper.insert(e);
            log.info("[审核] 高风险问题转人工审核 id={} query={}", e.getId(), query);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            log.warn("[审核] 建单失败，不拦截: {}", ex.getMessage());
            return false;
        }
    }

    /** 审核列表（按状态过滤） */
    public List<ReviewView> list(String statusFilter) {
        if (!status.isAvailable()) {
            return List.of();
        }
        try {
            QueryWrapper<AnswerReviewEntity> qw = new QueryWrapper<>();
            if (statusFilter != null && !statusFilter.isBlank()) {
                qw.eq("status", statusFilter);
            }
            qw.orderByDesc("id");
            return mapper.selectList(qw).stream().map(this::toView).toList();
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return List.of();
        }
    }

    /** 审核裁决 */
    public boolean review(long id, String decision, String reviewer) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            AnswerReviewEntity e = mapper.selectById(id);
            if (e == null || !"PENDING".equals(e.getStatus())) {
                return false;
            }
            e.setStatus("APPROVED".equals(decision) || "REJECTED".equals(decision) ? decision : "REJECTED");
            e.setReviewer(reviewer);
            e.setReviewedAt(LocalDateTime.now());
            mapper.updateById(e);
            log.info("[审核] id={} → {} by {}", id, e.getStatus(), reviewer);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return false;
        }
    }

    private ReviewView toView(AnswerReviewEntity e) {
        return new ReviewView(e.getId(), e.getQuery(), e.getAnswer(), e.getSources(),
                e.getEmployeeId(), e.getUser(), e.getStatus(), e.getReviewer(),
                e.getCreatedAt() != null ? e.getCreatedAt().toString() : "",
                e.getReviewedAt() != null ? e.getReviewedAt().toString() : "");
    }
}
