package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.AlertEventEntity;
import com.zzp.rag.mysql.mapper.AlertEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 告警事件存取（降级模式：业务库不可用返回 null，调用方自行降级）。
 * <p>
 * 生命周期：fire（条件命中，重触发原地更新）→ 确认（ACKNOWLEDGED）→ resolve（条件清除，RESOLVED）。
 * 一规则一行：历史 resolved 状态被重触发覆盖（count 重置）——v1 只保留当前状态，
 * 完整告警历史留待合规审计归档阶段。
 */
@Slf4j
@Service
public class AlertService {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_ACKNOWLEDGED = "ACKNOWLEDGED";
    public static final String STATUS_RESOLVED = "RESOLVED";

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AlertEventMapper mapper;
    private final MysqlStatus status;

    public AlertService(AlertEventMapper mapper, MysqlStatus status) {
        this.mapper = mapper;
        this.status = status;
    }

    public record AlertView(long id, String ruleCode, String level, String title, String detail,
                            String status, String firstAt, String lastAt, int count,
                            String ackBy, String ackAt) {}

    public record AlertStats(int unresolved, int acknowledged, int today) {}

    /**
     * 触发告警（幂等 upsert）：
     * - 无记录 → 新建 OPEN
     * - OPEN/ACKNOWLEDGED → 原地刷新 last_at/count/level/detail（不重复刷行）
     * - RESOLVED → 重开：status 回 OPEN、count 重置、first_at 更新
     * @return 当前行 id（0 = 降级未写）
     */
    public long fire(String ruleCode, String level, String title, String detail) {
        if (!status.isAvailable()) {
            return 0;
        }
        try {
            LocalDateTime now = LocalDateTime.now();
            AlertEventEntity existing = mapper.selectOne(
                    new QueryWrapper<AlertEventEntity>().eq("rule_code", ruleCode));
            if (existing == null) {
                AlertEventEntity e = new AlertEventEntity();
                e.setRuleCode(ruleCode);
                e.setLevel(level);
                e.setTitle(title);
                e.setDetail(detail);
                e.setStatus(STATUS_OPEN);
                e.setFirstAt(now);
                e.setLastAt(now);
                e.setCount(1);
                mapper.insert(e);
                log.warn("[告警] 新增 {} level={} title={}", ruleCode, level, title);
                return e.getId();
            }
            if (STATUS_RESOLVED.equals(existing.getStatus())) {
                // 恢复后复发：重开告警
                existing.setLevel(level);
                existing.setDetail(detail);
                existing.setStatus(STATUS_OPEN);
                existing.setFirstAt(now);
                existing.setLastAt(now);
                existing.setCount(1);
                existing.setAckBy(null);
                existing.setAckAt(null);
                mapper.updateById(existing);
                log.warn("[告警] 复发重开 {} level={}", ruleCode, level);
                return existing.getId();
            }
            existing.setLevel(level);
            existing.setDetail(detail);
            existing.setLastAt(now);
            existing.setCount((existing.getCount() == null ? 0 : existing.getCount()) + 1);
            mapper.updateById(existing);
            return existing.getId();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[告警] 写入失败，降级: {}", e.getMessage());
            return 0;
        }
    }

    /** 条件清除：未解决 → RESOLVED（保留次数与最后触发时间作现场） */
    public void resolve(String ruleCode) {
        if (!status.isAvailable()) {
            return;
        }
        try {
            AlertEventEntity existing = mapper.selectOne(
                    new QueryWrapper<AlertEventEntity>().eq("rule_code", ruleCode));
            if (existing != null && !STATUS_RESOLVED.equals(existing.getStatus())) {
                existing.setStatus(STATUS_RESOLVED);
                mapper.updateById(existing);
                log.info("[告警] 恢复 resolved={}", ruleCode);
            }
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[告警] 恢复写入失败: {}", e.getMessage());
        }
    }

    /** 人工确认：OPEN → ACKNOWLEDGED（记录确认人/时间） */
    public boolean ack(long id, String operator) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            AlertEventEntity e = mapper.selectById(id);
            if (e == null || !STATUS_OPEN.equals(e.getStatus())) {
                return false;
            }
            e.setStatus(STATUS_ACKNOWLEDGED);
            e.setAckBy(operator == null || operator.isBlank() ? "admin" : operator);
            e.setAckAt(LocalDateTime.now());
            mapper.updateById(e);
            log.info("[告警] 确认 id={} rule={} by={}", id, e.getRuleCode(), operator);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            log.warn("[告警] 确认失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 告警列表：未解决在前（OPEN → ACKNOWLEDGED → RESOLVED），同状态按最近触发倒序 */
    public List<AlertView> list(String statusFilter) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            QueryWrapper<AlertEventEntity> qw = new QueryWrapper<>();
            if (statusFilter != null && !statusFilter.isBlank()) {
                qw.eq("status", statusFilter);
            }
            qw.orderByAsc("FIELD(status,'OPEN','ACKNOWLEDGED','RESOLVED')").orderByDesc("last_at");
            return mapper.selectList(qw).stream().map(this::toView).toList();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[告警] 列表失败，降级: {}", e.getMessage());
            return null;
        }
    }

    /** 未解决统计（看板角标）：unresolved = OPEN + ACKNOWLEDGED；today = 今日触发/重开行数 */
    public AlertStats stats() {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            QueryWrapper<AlertEventEntity> qw = new QueryWrapper<>();
            qw.ne("status", STATUS_RESOLVED);
            long unresolved = mapper.selectCount(qw);
            long acknowledged = mapper.selectCount(
                    new QueryWrapper<AlertEventEntity>().eq("status", STATUS_ACKNOWLEDGED));
            long today = mapper.selectCount(new QueryWrapper<AlertEventEntity>()
                    .ge("last_at", LocalDateTime.now().toLocalDate().atStartOfDay()));
            return new AlertStats((int) unresolved, (int) acknowledged, (int) today);
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            return null;
        }
    }

    private AlertView toView(AlertEventEntity e) {
        return new AlertView(
                e.getId(), e.getRuleCode(), e.getLevel(), e.getTitle(), e.getDetail(),
                e.getStatus(),
                e.getFirstAt() != null ? e.getFirstAt().format(TS) : null,
                e.getLastAt() != null ? e.getLastAt().format(TS) : null,
                e.getCount() != null ? e.getCount() : 0,
                e.getAckBy(), e.getAckAt() != null ? e.getAckAt().format(TS) : null);
    }
}
