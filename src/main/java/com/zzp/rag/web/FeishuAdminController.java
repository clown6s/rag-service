package com.zzp.rag.web;

import com.zzp.rag.feishu.ContactSyncService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 飞书侧管理端点：通讯录同步（统一身份 + 三角色）。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin/feishu")
public class FeishuAdminController {

    private final ContactSyncService contactSyncService;

    public FeishuAdminController(ContactSyncService contactSyncService) {
        this.contactSyncService = contactSyncService;
    }

    /** 手动触发通讯录全量同步 */
    @PostMapping("/contact-sync")
    public ApiResponse<java.util.Map<String, Object>> sync() {
        int n = contactSyncService.sync();
        if (n < 0) {
            return ApiResponse.fail("同步失败：飞书通讯录权限未开通或拉取为空（需 contact:contact.base:readonly）");
        }
        return ApiResponse.ok(java.util.Map.of("synced", n));
    }

    /** 人工指定员工角色（同步不覆盖） */
    @PostMapping("/set-role")
    public ApiResponse<String> setRole(@RequestBody SetRoleRequest req) {
        if (req.openId() == null || req.openId().isBlank() || req.role() == null) {
            return ApiResponse.fail("openId/role 不能为空");
        }
        return contactSyncService.setRole(req.openId(), req.role())
                ? ApiResponse.ok("已指定角色：" + req.role())
                : ApiResponse.fail("指定失败：员工不存在（先执行通讯录同步）");
    }

    public record SetRoleRequest(String openId, String role) {}
}
