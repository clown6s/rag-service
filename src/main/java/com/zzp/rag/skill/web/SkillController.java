package com.zzp.rag.skill.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.skill.service.SkillService;
import com.zzp.rag.web.ApiResponse;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 技能注册中心 API
 * GET    /api/rag/admin/skills                 — 技能列表（含已授权员工数）
 * POST   /api/rag/admin/skills                 — 创建
 * POST   /api/rag/admin/skills/upload          — 上传技能包（zip：skill.json + SKILL.md，热注册）
 * PUT    /api/rag/admin/skills/{id}            — 更新（code/toolType 不可改）
 * DELETE /api/rag/admin/skills/{id}            — 删除（级联清授权）
 * GET    /api/rag/admin/employees/{id}/skills  — 员工已授权技能
 * PUT    /api/rag/admin/employees/{id}/skills  — 全量替换授权 {skillIds:[...]}
 */
@RestController
@RequestMapping("/api/rag/admin")
public class SkillController {

    private final SkillService skillService;
    private final ObjectMapper objectMapper;

    public SkillController(SkillService skillService, ObjectMapper objectMapper) {
        this.skillService = skillService;
        this.objectMapper = objectMapper;
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

    /**
     * 技能包上传热注册（zip 包）：
     * - skill.json：{"code","name","description","toolType","mcpConfig","toolNames","status"}
     * - SKILL.md：技能说明文档（存 skillMd，暂存 DB 供后续渲染）
     * 解析成功后走 SkillService.create（校验/唯一性/快照失效与手工创建完全一致），即时生效。
     */
    @PostMapping("/skills/upload")
    public ApiResponse<SkillService.SkillView> upload(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ApiResponse.fail("文件为空");
        }
        if (file.getSize() > 2 * 1024 * 1024) {
            return ApiResponse.fail("技能包不能超过 2MB");
        }
        try {
            JsonNode manifest = null;
            String skillMd = "";
            try (ZipInputStream zip = new ZipInputStream(file.getInputStream(), StandardCharsets.UTF_8)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String name = entry.getName().toLowerCase();
                    byte[] bytes = zip.readAllBytes();
                    if (name.endsWith("skill.json")) {
                        manifest = objectMapper.readTree(bytes);
                    } else if (name.endsWith("skill.md")) {
                        skillMd = new String(bytes, StandardCharsets.UTF_8);
                    }
                }
            }
            if (manifest == null) {
                return ApiResponse.fail("技能包缺少 skill.json");
            }
            // 提取为 effectively-final 局部量（lambda 捕获要求）
            final JsonNode m = manifest;
            final String md = skillMd.isBlank() ? null : skillMd;
            String code = m.path("code").asText("");
            String name = m.path("name").asText("");
            String description = m.path("description").asText("");
            String toolType = m.path("toolType").asText("mcp");
            String mcpConfig = m.has("mcpConfig") && !m.get("mcpConfig").isNull()
                    ? m.get("mcpConfig").toString() : null;
            String toolNames = m.path("toolNames").asText("");
            Boolean statusFlag = m.has("status") ? m.get("status").asBoolean() : Boolean.TRUE;
            final String fCode = code, fName = name, fDesc = description, fType = toolType,
                    fMcp = mcpConfig, fTools = toolNames;
            final Boolean fStatus = statusFlag;
            return doWrite(() -> skillService.create(fCode, fName, fDesc,
                    md, fType, fMcp, fTools, fStatus));
        } catch (IllegalArgumentException e) {
            return ApiResponse.fail(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.fail("技能包解析失败：" + e.getMessage());
        }
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
