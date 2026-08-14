package com.zzp.rag.audit;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.AuditEventEntity;
import com.zzp.rag.mysql.mapper.AuditEventMapper;
import com.zzp.rag.mysql.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 告警规则引擎 — 定时评估审计聚合数据，产出/恢复告警（合规审计的主动侧）。
 * <p>
 * 规则（软依赖：评估失败不阻断任何链路）：
 * 1. error_rate            窗口错误率 ≥ 阈值且样本 ≥ N → WARNING；≥ 2× 阈值 → CRITICAL
 * 2. consecutive_failures  最近 5 条审计事件全失败 → CRITICAL
 * 3. slow_response         窗口平均端到端耗时 ≥ 阈值且样本 ≥ N → WARNING
 * 4. mysql_down            业务库不可用 → CRITICAL（本规则不依赖 DB：
 *                          不可用时只记 ERROR 日志，恢复后补写一条 RESOLVED 历史）
 * <p>
 * 状态机：条件命中 fire（重触发原地更新）→ 条件清除 resolve → 复发重开（AlertService）。
 */
@Slf4j
@Component
public class AlertRuleEngine {

    public static final String RULE_ERROR_RATE = "error_rate";
    public static final String RULE_CONSECUTIVE_FAILURES = "consecutive_failures";
    public static final String RULE_SLOW_RESPONSE = "slow_response";
    public static final String RULE_MYSQL_DOWN = "mysql_down";

    private final AuditEventMapper auditMapper;
    private final AlertService alertService;
    private final MysqlStatus mysqlStatus;

    @Value("${rag.alert.window-minutes:5}")
    private int windowMinutes;

    @Value("${rag.alert.error-rate-threshold:0.3}")
    private double errorRateThreshold;

    @Value("${rag.alert.error-rate-min-samples:10}")
    private int errorRateMinSamples;

    @Value("${rag.alert.slow-ms:30000}")
    private long slowMs;

    @Value("${rag.alert.slow-min-samples:5}")
    private int slowMinSamples;

    @Value("${rag.alert.consecutive-failures:5}")
    private int consecutiveFailures;

    public AlertRuleEngine(AuditEventMapper auditMapper,
                           AlertService alertService, MysqlStatus mysqlStatus) {
        this.auditMapper = auditMapper;
        this.alertService = alertService;
        this.mysqlStatus = mysqlStatus;
    }

    // ==================== 业务库不可用：规则 4 的内存状态 ====================

    /** 业务库上次不可用的时间（null = 当前可用）；恢复后用于补写故障记录 */
    private volatile LocalDateTime mysqlDownSince;

    /** 每 60s 评估一轮（首次延迟 60s，让审计聚合器先跑起来） */
    @Scheduled(fixedDelayString = "${rag.alert.interval-ms:60000}", initialDelay = 60_000)
    public void evaluate() {
        // 规则 4 优先：不依赖 DB
        if (!mysqlStatus.isAvailable()) {
            if (mysqlDownSince == null) {
                mysqlDownSince = LocalDateTime.now();
                log.error("[告警] 业务库不可用（规则 1-3 暂停评估，恢复后补写故障记录）");
            }
            return;
        }
        if (mysqlDownSince != null) {
            LocalDateTime downSince = mysqlDownSince;
            mysqlDownSince = null;
            // 恢复：补写一条告警历史（fire 后立即 resolve，保留现场供排查）
            alertService.fire(RULE_MYSQL_DOWN, "CRITICAL",
                    "业务库连接中断后已恢复",
                    "中断开始: " + downSince + "，恢复: " + LocalDateTime.now());
            alertService.resolve(RULE_MYSQL_DOWN);
            log.warn("[告警] 业务库已恢复，中断时长 {} 秒",
                    java.time.Duration.between(downSince, LocalDateTime.now()).toSeconds());
        }

        try {
            evaluateErrorRate();
            evaluateConsecutiveFailures();
            evaluateSlowResponse();
        } catch (Exception e) {
            // 软依赖：单规则异常不阻断其他规则
            log.warn("[告警] 评估异常: {}", e.getMessage());
        }
    }

    /** 规则 1：窗口错误率 */
    private void evaluateErrorRate() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        QueryWrapper<AuditEventEntity> qw = new QueryWrapper<AuditEventEntity>().ge("created_at", since);
        long total = auditMapper.selectCount(qw);
        if (total < errorRateMinSamples) {
            alertService.resolve(RULE_ERROR_RATE);
            return;
        }
        long errors = auditMapper.windowErrorCount(since);
        double rate = (double) errors / total;
        if (rate >= errorRateThreshold) {
            String level = rate >= errorRateThreshold * 2 ? "CRITICAL" : "WARNING";
            alertService.fire(RULE_ERROR_RATE, level,
                    "请求错误率过高",
                    String.format("近 %d 分钟 %d 次请求中 %d 次失败（%.1f%% ≥ %.1f%%）",
                            windowMinutes, total, errors, rate * 100, errorRateThreshold * 100));
        } else {
            alertService.resolve(RULE_ERROR_RATE);
        }
    }

    /** 规则 2：最近 N 条审计事件连续失败 */
    private void evaluateConsecutiveFailures() {
        List<AuditEventEntity> last = auditMapper.selectList(
                new QueryWrapper<AuditEventEntity>()
                        .orderByDesc("id").last("LIMIT " + consecutiveFailures));
        boolean allFailed = last.size() == consecutiveFailures
                && last.stream().allMatch(e -> !"SUCCESS".equals(e.getStatus()));
        if (allFailed) {
            String recent = last.get(0).getError() != null ? last.get(0).getError() : "未知原因";
            alertService.fire(RULE_CONSECUTIVE_FAILURES, "CRITICAL",
                    "连续请求失败",
                    String.format("最近 %d 条请求全部失败（最新错误: %s）",
                            consecutiveFailures, truncate(recent, 200)));
        } else {
            alertService.resolve(RULE_CONSECUTIVE_FAILURES);
        }
    }

    /** 规则 3：窗口平均耗时过高 */
    private void evaluateSlowResponse() {
        LocalDateTime since = LocalDateTime.now().minusMinutes(windowMinutes);
        long total = auditMapper.selectCount(
                new QueryWrapper<AuditEventEntity>().ge("created_at", since));
        if (total < slowMinSamples) {
            alertService.resolve(RULE_SLOW_RESPONSE);
            return;
        }
        Double avg = auditMapper.windowAvgMs(since);
        if (avg != null && avg >= slowMs) {
            alertService.fire(RULE_SLOW_RESPONSE, "WARNING",
                    "响应耗时过高",
                    String.format("近 %d 分钟 %d 次请求平均耗时 %.0f ms（≥ %d ms）",
                            windowMinutes, total, avg, slowMs));
        } else {
            alertService.resolve(RULE_SLOW_RESPONSE);
        }
    }

    private String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) : s;
    }
}
