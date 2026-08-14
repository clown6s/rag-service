package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.AuditEventEntity;
import com.zzp.rag.mysql.mapper.AuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 审计看板查询（降级模式：业务库不可用返回 null，Controller 转 fail 提示）。
 */
@Slf4j
@Service
public class AuditQueryService {

    private final AuditEventMapper mapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;

    public AuditQueryService(AuditEventMapper mapper, MysqlStatus status, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    public record HourlyStat(String hour, int count, int errorCount, long avgTotalMs) {}

    public record AuditStats(int todayRequests, double successRate, long avgTotalMs,
                             int errorCount, List<HourlyStat> hourly) {}

    public record AuditEventView(
            String traceId, String timestamp, String user, String ip, String endpoint,
            String category, String query, int historyCount, List<String> sources,
            long retrievalMs, boolean rerankDegraded, int graphContextChars,
            String answerPreview, int answerLength, long totalMs, String status, String error) {}

    public record PageResult<T>(long total, int page, int size, List<T> items) {}

    public AuditStats stats() {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            Map<String, Object> overview = mapper.todayOverview();
            long total = num(overview.get("total"));
            long success = num(overview.get("success"));
            long avg = num(overview.get("avg_ms"));
            List<HourlyStat> hourly = mapper.hourlyDistribution().stream()
                    .map(m -> new HourlyStat(
                            str(m.get("hour")),
                            (int) num(m.get("cnt")),
                            (int) num(m.get("err_cnt")),
                            num(m.get("avg_ms"))))
                    .toList();
            double rate = total == 0 ? 100.0 : success * 100.0 / total;
            return new AuditStats((int) total, rate, avg, (int) (total - success), hourly);
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[审计查询] 统计失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public PageResult<AuditEventView> events(int page, int size, String statusFilter, String keyword) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            QueryWrapper<AuditEventEntity> qw = new QueryWrapper<>();
            if (statusFilter != null && !statusFilter.isBlank()) {
                qw.eq("status", statusFilter);
            }
            if (keyword != null && !keyword.isBlank()) {
                // user 是保留字，反引号包裹（Wrapper 列名原样进 SQL，MP 不转义）
                qw.and(w -> w.like("`user`", keyword).or().like("query", keyword));
            }
            qw.orderByDesc("id");
            Page<AuditEventEntity> result = mapper.selectPage(new Page<>(page, size), qw);
            List<AuditEventView> items = result.getRecords().stream().map(this::toView).toList();
            return new PageResult<>(result.getTotal(), page, size, items);
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[审计查询] 事件查询失败，降级: {}", e.getMessage());
            return null;
        }
    }

    private AuditEventView toView(AuditEventEntity e) {
        List<String> sources = List.of();
        if (e.getSources() != null && !e.getSources().isBlank()) {
            try {
                sources = objectMapper.readValue(e.getSources(), new TypeReference<List<String>>() {});
            } catch (Exception ignored) {
                // JSON 损坏按空处理
            }
        }
        return new AuditEventView(
                e.getTraceId(), e.getTimestamp(), e.getUser(), e.getIp(), e.getEndpoint(),
                e.getCategory(), e.getQuery(), e.getHistoryCount() != null ? e.getHistoryCount() : 0,
                sources, e.getRetrievalMs() != null ? e.getRetrievalMs() : 0,
                Boolean.TRUE.equals(e.getRerankDegraded()),
                e.getGraphContextChars() != null ? e.getGraphContextChars() : 0,
                e.getAnswerPreview(), e.getAnswerLength() != null ? e.getAnswerLength() : 0,
                e.getTotalMs() != null ? e.getTotalMs() : 0, e.getStatus(), e.getError());
    }

    private static long num(Object o) {
        if (o instanceof Number n) {
            return n.longValue();
        }
        return 0;
    }

    private static String str(Object o) {
        return o == null ? "" : o.toString();
    }
}
