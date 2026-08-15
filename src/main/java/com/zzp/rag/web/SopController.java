package com.zzp.rag.web;

import com.zzp.rag.mysql.service.SopStoreService;
import com.zzp.rag.workflow.SopDefinition;
import com.zzp.rag.workflow.SopRegistry;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

/**
 * SOP 可视化配置（后台 CRUD）：DB SOP 与内置 SOP 同构，保存即热生效。
 */
@RestController
@RequestMapping("/api/rag/admin/sops")
public class SopController {

    public record SaveSopRequest(String code, String name, String keywords,
                                 List<SopDefinition.SopStep> steps, Boolean enabled) {}

    public record SopFullView(String code, String name, List<String> keywords,
                              List<SopDefinition.SopStep> steps, boolean enabled,
                              boolean builtin, String updatedAt) {}

    private final SopStoreService sopStore;
    private final SopRegistry sopRegistry;

    public SopController(SopStoreService sopStore, SopRegistry sopRegistry) {
        this.sopStore = sopStore;
        this.sopRegistry = sopRegistry;
    }

    /** 全部定义：内置 + DB（内置只读，DB 可编辑） */
    @GetMapping
    public ApiResponse<List<SopFullView>> list() {
        List<SopFullView> out = new ArrayList<>();
        for (SopDefinition def : sopRegistry.definitions()) {
            out.add(new SopFullView(def.code(), def.name(), def.keywords(), def.steps(),
                    true, isBuiltinCode(def.code()), ""));
        }
        // DB 列表覆盖（含禁用与更新时间）
        for (SopStoreService.SopView v : sopStore.list()) {
            out.removeIf(x -> x.code().equals(v.code()));
            out.add(new SopFullView(v.code(), v.name(), v.keywords(), v.steps(),
                    v.enabled(), v.builtin(), v.updatedAt()));
        }
        return ApiResponse.ok(out);
    }

    @PostMapping
    public ApiResponse<Boolean> save(@RequestBody SaveSopRequest req) {
        if (req.code() == null || req.code().isBlank() || isBuiltinCode(req.code())) {
            return ApiResponse.fail("code 必填且不可覆盖内置定义");
        }
        if (req.name() == null || req.name().isBlank() || req.keywords() == null || req.keywords().isBlank()) {
            return ApiResponse.fail("name/keywords 必填");
        }
        boolean ok = sopStore.save(req.code(), req.name(), req.keywords(), req.steps(),
                req.enabled() == null || req.enabled());
        if (ok) {
            sopRegistry.reload();
            return ApiResponse.ok(true);
        }
        return ApiResponse.fail("业务库未连接，保存失败");
    }

    @PostMapping("/{code}/toggle")
    public ApiResponse<Boolean> toggle(@PathVariable String code, @RequestBody java.util.Map<String, Boolean> body) {
        boolean ok = sopStore.toggle(code, Boolean.TRUE.equals(body.get("enabled")));
        if (ok) {
            sopRegistry.reload();
            return ApiResponse.ok(true);
        }
        return ApiResponse.fail("切换失败（内置 SOP 或业务库未连接）");
    }

    @DeleteMapping("/{code}")
    public ApiResponse<Boolean> delete(@PathVariable String code) {
        boolean ok = sopStore.delete(code);
        if (ok) {
            sopRegistry.reload();
            return ApiResponse.ok(true);
        }
        return ApiResponse.fail("删除失败（内置 SOP 或业务库未连接）");
    }

    private static boolean isBuiltinCode(String code) {
        return "leave_apply".equals(code) || "onboard_guide".equals(code);
    }
}
