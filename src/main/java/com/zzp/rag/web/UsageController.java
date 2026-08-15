package com.zzp.rag.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 模型用量（成本看板）：近 N 天按 日期×模型×用途 聚合。
 * token 数为字符量换算估算（中文约 1 token ≈ 1.2 字符），看板标注"估算"。
 */
@RestController
@RequestMapping("/api/rag/admin/usage")
public class UsageController {

    private final UsageTracker usageTracker;

    public UsageController(UsageTracker usageTracker) {
        this.usageTracker = usageTracker;
    }

    @GetMapping
    public ApiResponse<List<UsageTracker.DailyUsage>> usage(
            @RequestParam(defaultValue = "7") int days) {
        return ApiResponse.ok(usageTracker.usage(days));
    }
}
