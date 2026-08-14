package com.zzp.rag.skill.web;

import com.zzp.rag.skill.service.SkillService;
import com.zzp.rag.web.ApiResponse;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.function.Supplier;

/**
 * 技能注册中心 API
 * GET    /api/rag/admin/skills                 — 技能列表（含已授权员工数）
 * POST   /api/rag/admin/skills                 — 创建
 * PUT    /api/rag/admin/skills/{id}            — 更新（code/toolType 不可改）
 * DELETE /api/rag/admin/skills/{id}            — 删除（级联清授权）
 * GET    /api/rag/admin/employees/{id}/skills  — 员工已授权技能
 * PUT    /api/rag/admin/employees/{id}/skills  — 全量替换授权 {skillIds:[...]}
 */
@RestController
@RequestMapping("/api/rag/admin")
public class SkillController {

    private final SkillService skillService;

    public SkillController(SkillService skillService) {
        this.skillService = skillService;
    }

    public record SkillRequest(String code, String name, String description, String skillMd,
                               String toolType, String mcpConfig, String toolNames, Boolean status) {}

    public record SkillIdsRequest(List<Long> skillIds) {}

    @GetMapping("/skills")
    public ApiResponse<List<SkillService.SkillView>> list() {
        List<SkillService.SkillView> list = skillService.list();
        return list != null ? ApiResponse.ok(list) : ApiResponse.fail("业务库未连接，请联系管理员");
    }

    @GetMapping("/skills/{id}")
    public ApiResponse<SkillService.SkillView> get(@PathVariable long id) {
        SkillService.SkillView view = skillService.get(id);
        if (view == null) {
            return ApiResponse.fail("技能不存在");
        }
        return ApiResponse.ok(view);
    }

    @PostMapping("/skills")
    public ApiResponse<SkillService.SkillView> create(@RequestBody SkillRequest req) {
        return doWrite(() -> skillService.create(req.code(), req.name(), req.description(),
                req.skillMd(), req.toolType(), req.mcpConfig(), req.toolNames(), req.status()));
    }

    @PutMapping("/skills/{id}")
    public ApiResponse<SkillService.SkillView> update(@PathVariable long id,
                                                      @RequestBody SkillRequest req) {
        return doWrite(() -> skillService.update(id, req.name(), req.description(), req.skillMd(),
                req.mcpConfig(), req.toolNames(), req.status()));
    }

    @DeleteMapping("/skills/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        try {
            return skillService.delete(id)
                    ? ApiResponse.ok(null)
                    : ApiResponse.fail("技能不存在");
        } catch (Exception e) {
            return ApiResponse.fail("业务库未连接，请联系管理员");
        }
    }

    @GetMapping("/employees/{id}/skills")
    public ApiResponse<List<SkillService.SkillView>> employeeSkills(@PathVariable long id) {
        List<SkillService.SkillView> list = skillService.getEmployeeSkills(id);
        return list != null ? ApiResponse.ok(list) : ApiResponse.fail("业务库未连接，请联系管理员");
    }

    @PutMapping("/employees/{id}/skills")
    public ApiResponse<List<SkillService.SkillView>> replaceEmployeeSkills(
            @PathVariable long id, @RequestBody(required = false) SkillIdsRequest req) {
        List<SkillService.SkillView> list = skillService.replaceEmployeeSkills(
                id, req != null ? req.skillIds() : List.of());
        return list != null ? ApiResponse.ok(list) : ApiResponse.fail("业务库未连接，请联系管理员");
    }

    private ApiResponse<SkillService.SkillView> doWrite(
            Supplier<SkillService.SkillView> action) {
        try {
            SkillService.SkillView view = action.get();
            return view != null ? ApiResponse.ok(view) : ApiResponse.fail("业务库未连接，请联系管理员");
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("操作失败，请联系管理员");
        }
    }
}
