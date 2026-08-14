package com.zzp.rag.web;

import com.zzp.rag.audit.AlertRuleEngine;
import com.zzp.rag.mysql.service.AlertService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 告警中心 API（合规审计主动侧）
 * <p>
 * GET  /api/rag/admin/alerts           — 告警列表（未解决在前）
 * GET  /api/rag/admin/alerts/stats     — 未解决/已确认/今日统计（看板角标）
 * POST /api/rag/admin/alerts/{id}/ack  — 人工确认
 * POST /api/rag/admin/alerts/evaluate  — 手动触发一轮规则评估（排查/演示用）
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin/alerts")
public class AlertController {

    private final AlertService alertService;
    private final AlertRuleEngine ruleEngine;

    public AlertController(AlertService alertService, AlertRuleEngine ruleEngine) {
        this.alertService = alertService;
        this.ruleEngine = ruleEngine;
    }

    @GetMapping
    public ApiResponse<List<AlertService.AlertView>> list(
            @RequestParam(required = false) String status) {
        List<AlertService.AlertView> alerts = alertService.list(status);
        if (alerts == null) {
            return ApiResponse.fail("业务库不可用，请稍后再试");
        }
        return ApiResponse.ok(alerts);
    }

    @GetMapping("/stats")
    public ApiResponse<AlertService.AlertStats> stats() {
        AlertService.AlertStats stats = alertService.stats();
        if (stats == null) {
            return ApiResponse.ok(new AlertService.AlertStats(0, 0, 0));   // 软依赖：不可用按零展示
        }
        return ApiResponse.ok(stats);
    }

    @PostMapping("/{id}/ack")
    public ApiResponse<Map<String, Object>> ack(@PathVariable long id,
                                                @RequestBody(required = false) Map<String, String> body) {
        String operator = body == null ? null : body.get("operator");
        boolean ok = alertService.ack(id, operator);
        if (!ok) {
            return ApiResponse.fail("确认失败（告警不存在或已处理，或业务库不可用）");
        }
        log.info("[告警] 人工确认 id={} by={}", id, operator);
        return ApiResponse.ok(Map.of("acked", true));
    }

    /** 手动触发一轮规则评估（定时任务之外，排查/演示用） */
    @PostMapping("/evaluate")
    public ApiResponse<Map<String, Object>> evaluate() {
        ruleEngine.evaluate();
        return ApiResponse.ok(Map.of("evaluated", true));
    }
}
