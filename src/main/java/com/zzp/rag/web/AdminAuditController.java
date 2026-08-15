package com.zzp.rag.web;

import com.zzp.rag.mysql.service.AuditQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 审计看板 API
 * GET /api/rag/admin/audit/stats  — 今日概览 + 近24小时分布
 * GET /api/rag/admin/audit/events — 事件分页（status/keyword 过滤）
 */
@RestController
@RequestMapping("/api/rag/admin/audit")
public class AdminAuditController {

    private final AuditQueryService auditQueryService;

    public AdminAuditController(AuditQueryService auditQueryService) {
        this.auditQueryService = auditQueryService;
    }

    @GetMapping("/stats")
    public ApiResponse<AuditQueryService.AuditStats> stats() {
        AuditQueryService.AuditStats stats = auditQueryService.stats();
        return stats != null ? ApiResponse.ok(stats) : ApiResponse.fail("业务库未连接，请检查 MySQL 服务");
    }

    /** 数据看板：近 N 天趋势 + 热门问题 + 路由分布 + 员工活跃度 */
    @GetMapping("/dashboard")
    public ApiResponse<AuditQueryService.DashboardStats> dashboard(
            @RequestParam(defaultValue = "14") int days) {
        AuditQueryService.DashboardStats stats = auditQueryService.dashboard(days);
        return stats != null ? ApiResponse.ok(stats) : ApiResponse.fail("业务库未连接，请检查 MySQL 服务");
    }

    @GetMapping("/events")
    public ApiResponse<AuditQueryService.PageResult<AuditQueryService.AuditEventView>> events(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword) {
        int safePage = Math.max(page, 1);
        int safeSize = Math.min(Math.max(size, 1), 200);
        AuditQueryService.PageResult<AuditQueryService.AuditEventView> result =
                auditQueryService.events(safePage, safeSize, status, keyword);
        return result != null ? ApiResponse.ok(result) : ApiResponse.fail("业务库未连接，请检查 MySQL 服务");
    }
}
