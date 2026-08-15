package com.zzp.rag.web;

import com.zzp.rag.mysql.service.DigitalEmployeeService;
import org.springframework.web.bind.annotation.*;

/**
 * 数字员工配置 API（存储层 CRUD + 生命周期治理）
 * GET /api/rag/admin/employees — 列表
 * POST /api/rag/admin/employees — 创建（默认 ONLINE）
 * PUT /api/rag/admin/employees/{id} — 更新
 * PATCH /api/rag/admin/employees/{id}/lifecycle — 生命周期流转（DRAFT/TESTING/ONLINE/OFFLINE）
 * DELETE /api/rag/admin/employees/{id} — 删除
 */
@RestController
@RequestMapping("/api/rag/admin/employees")
public class EmployeeController {

    private final DigitalEmployeeService employeeService;

    public EmployeeController(DigitalEmployeeService employeeService) {
        this.employeeService = employeeService;
    }

    public record EmployeeRequest(String name, String avatar, String description,
                                  String systemPrompt, String knowledgeScope, Boolean status,
                                  String dept) {}

    @GetMapping
    public ApiResponse<java.util.List<DigitalEmployeeService.EmployeeView>> list() {
        java.util.List<DigitalEmployeeService.EmployeeView> list = employeeService.list();
        return list != null ? ApiResponse.ok(list) : ApiResponse.fail("业务库未连接，请联系管理员");
    }

    @GetMapping("/{id}")
    public ApiResponse<DigitalEmployeeService.EmployeeView> get(@PathVariable long id) {
        DigitalEmployeeService.EmployeeView view = employeeService.get(id);
        if (view == null) {
            return ApiResponse.fail("数字员工不存在");
        }
        return ApiResponse.ok(view);
    }

    @PostMapping
    public ApiResponse<DigitalEmployeeService.EmployeeView> create(@RequestBody EmployeeRequest req) {
        return doWrite(() -> employeeService.create(req.name(), req.avatar(), req.description(),
                req.systemPrompt(), req.knowledgeScope(), req.status(), req.dept()));
    }

    @PutMapping("/{id}")
    public ApiResponse<DigitalEmployeeService.EmployeeView> update(@PathVariable long id,
                                                                   @RequestBody EmployeeRequest req) {
        return doWrite(() -> employeeService.update(id, req.name(), req.avatar(), req.description(),
                req.systemPrompt(), req.knowledgeScope(), req.status(), req.dept()));
    }

    /** 生命周期流转（治理状态机：DRAFT/TESTING/ONLINE/OFFLINE；运行时仅 ONLINE/TESTING 可用） */
    @PatchMapping("/{id}/lifecycle")
    public ApiResponse<DigitalEmployeeService.EmployeeView> transition(
            @PathVariable long id, @RequestBody java.util.Map<String, String> body) {
        return doWrite(() -> employeeService.transition(id, body.get("lifecycle")));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            return employeeService.delete(id)
                    ? ApiResponse.ok(null)
                    : ApiResponse.fail("数字员工不存在");
        } catch (Exception e) {
            return ApiResponse.fail("业务库未连接，请联系管理员");
        }
    }

    /** 写操作统一包装：校验错误给友好消息；业务库不可用给降级提示 */
    private ApiResponse<DigitalEmployeeService.EmployeeView> doWrite(
            java.util.function.Supplier<DigitalEmployeeService.EmployeeView> action) {
        try {
            DigitalEmployeeService.EmployeeView view = action.get();
            return view != null ? ApiResponse.ok(view) : ApiResponse.fail("业务库未连接，请联系管理员");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("操作失败，请联系管理员");
        }
    }
}
