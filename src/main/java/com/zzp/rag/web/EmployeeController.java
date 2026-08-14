package com.zzp.rag.web;

import com.zzp.rag.mysql.service.DigitalEmployeeService;
import org.springframework.web.bind.annotation.*;

/**
 * 数字员工配置 API（存储层 CRUD；运行时绑定留阶段 4）
 * GET /api/rag/admin/employees — 列表
 * POST /api/rag/admin/employees — 创建
 * PUT /api/rag/admin/employees/{id} — 更新
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
                                  String systemPrompt, String knowledgeScope, Boolean status) {}

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
                req.systemPrompt(), req.knowledgeScope(), req.status()));
    }

    @PutMapping("/{id}")
    public ApiResponse<DigitalEmployeeService.EmployeeView> update(@PathVariable long id,
                                                                   @RequestBody EmployeeRequest req) {
        return doWrite(() -> employeeService.update(id, req.name(), req.avatar(), req.description(),
                req.systemPrompt(), req.knowledgeScope(), req.status()));
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
