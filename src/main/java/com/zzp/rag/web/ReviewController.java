package com.zzp.rag.web;

import com.zzp.rag.mysql.service.ReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 答案审核流（合规）：高风险问题答案先审后发。
 */
@RestController
@RequestMapping("/api/rag/admin/review")
public class ReviewController {

    private final ReviewService reviewService;

    public ReviewController(ReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping
    public ApiResponse<List<ReviewService.ReviewView>> list(
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(reviewService.list(status));
    }

    @PostMapping("/{id}/approve")
    public ApiResponse<Boolean> approve(@PathVariable long id) {
        String reviewer = org.slf4j.MDC.get(com.zzp.rag.audit.AuditEvent.MDC_USER_KEY);
        return reviewService.review(id, "APPROVED", reviewer)
                ? ApiResponse.ok(true) : ApiResponse.fail("审核失败（记录不存在/已处理/业务库未连接）");
    }

    @PostMapping("/{id}/reject")
    public ApiResponse<Boolean> reject(@PathVariable long id) {
        String reviewer = org.slf4j.MDC.get(com.zzp.rag.audit.AuditEvent.MDC_USER_KEY);
        return reviewService.review(id, "REJECTED", reviewer)
                ? ApiResponse.ok(true) : ApiResponse.fail("审核失败（记录不存在/已处理/业务库未连接）");
    }
}
