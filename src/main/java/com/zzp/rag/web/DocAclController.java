package com.zzp.rag.web;

import com.zzp.rag.mysql.service.DocAclService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文档级访问控制管理：为文档设置授权员工（未设置 = 全员可见）。
 */
@RestController
@RequestMapping("/api/rag/admin/acl")
public class DocAclController {

    public record SetAclRequest(String docId, List<Long> employeeIds) {}

    private final DocAclService docAclService;

    public DocAclController(DocAclService docAclService) {
        this.docAclService = docAclService;
    }

    @GetMapping
    public ApiResponse<List<DocAclService.AclView>> list() {
        return ApiResponse.ok(docAclService.listAcls());
    }

    /** 设置文档授权：employeeIds 为空 = 恢复全员可见 */
    @PostMapping
    public ApiResponse<Boolean> set(@RequestBody SetAclRequest req) {
        if (req.docId() == null || req.docId().isBlank()) {
            return ApiResponse.fail("docId 必填");
        }
        String operator = org.slf4j.MDC.get(com.zzp.rag.audit.AuditEvent.MDC_USER_KEY);
        boolean ok = docAclService.setAcl(req.docId(), req.employeeIds(), operator);
        return ok ? ApiResponse.ok(true) : ApiResponse.fail("业务库未连接，设置失败");
    }

    @DeleteMapping("/{docId}")
    public ApiResponse<Boolean> remove(@PathVariable String docId) {
        boolean ok = docAclService.removeAcl(docId);
        return ok ? ApiResponse.ok(true) : ApiResponse.fail("移除失败（记录不存在或业务库未连接）");
    }
}
