package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.SopEntity;
import com.zzp.rag.mysql.mapper.SopMapper;
import com.zzp.rag.workflow.SopDefinition;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * SOP 持久化与解析：sop 表 → SopDefinition（与内置 SOP 同构，引擎零改动）。
 * 软依赖：业务库不可用返回空列表（内置 SOP 仍工作）。
 */
@Slf4j
@Service
public class SopStoreService {

    public record SopView(String code, String name, List<String> keywords,
                          List<SopDefinition.SopStep> steps, boolean enabled,
                          boolean builtin, String updatedAt) {}

    private final SopMapper mapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;

    public SopStoreService(SopMapper mapper, MysqlStatus status, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    /** 加载启用中的 DB SOP → SopDefinition 列表（禁用/损坏的跳过） */
    public List<SopDefinition> loadEnabled() {
        if (!status.isAvailable()) {
            return List.of();
        }
        try {
            List<SopDefinition> out = new ArrayList<>();
            for (SopEntity e : mapper.selectList(new QueryWrapper<SopEntity>().eq("enabled", 1))) {
                SopDefinition def = toDefinition(e);
                if (def != null) {
                    out.add(def);
                }
            }
            return out;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[SOP] DB 加载失败，仅内置可用: {}", e.getMessage());
            return List.of();
        }
    }

    /** 管理页列表（含禁用） */
    public List<SopView> list() {
        if (!status.isAvailable()) {
            return List.of();
        }
        try {
            return mapper.selectList(new QueryWrapper<SopEntity>().orderByDesc("updated_at"))
                    .stream().map(this::toView).toList();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            return List.of();
        }
    }

    /** 创建/更新（code 唯一键 upsert）；builtin=0 固定（内置代码不可覆盖） */
    public boolean save(String code, String name, String keywords, List<SopDefinition.SopStep> steps,
                        boolean enabled) {
        if (!status.isAvailable() || code == null || code.isBlank()) {
            return false;
        }
        try {
            String stepsJson = objectMapper.writeValueAsString(steps == null ? List.of() : steps);
            SopEntity e = mapper.selectOne(new QueryWrapper<SopEntity>().eq("code", code));
            if (e == null) {
                e = new SopEntity();
                e.setCode(code);
                e.setName(name);
                e.setKeywords(keywords);
                e.setStepsJson(stepsJson);
                e.setEnabled(enabled ? 1 : 0);
                e.setBuiltin(0);
                mapper.insert(e);
            } else {
                if (e.getBuiltin() != null && e.getBuiltin() == 1) {
                    return false;   // 内置 SOP 不可覆盖
                }
                e.setName(name);
                e.setKeywords(keywords);
                e.setStepsJson(stepsJson);
                e.setEnabled(enabled ? 1 : 0);
                mapper.updateById(e);
            }
            log.info("[SOP] 保存 {} enabled={}", code, enabled);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            log.warn("[SOP] 保存失败: {}", ex.getMessage());
            return false;
        }
    }

    /** 启停切换 */
    public boolean toggle(String code, boolean enabled) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            SopEntity e = mapper.selectOne(new QueryWrapper<SopEntity>().eq("code", code));
            if (e == null || (e.getBuiltin() != null && e.getBuiltin() == 1)) {
                return false;
            }
            e.setEnabled(enabled ? 1 : 0);
            mapper.updateById(e);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return false;
        }
    }

    /** 删除（内置保护） */
    public boolean delete(String code) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            SopEntity e = mapper.selectOne(new QueryWrapper<SopEntity>().eq("code", code));
            if (e == null || (e.getBuiltin() != null && e.getBuiltin() == 1)) {
                return false;
            }
            mapper.deleteById(e.getId());
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return false;
        }
    }

    private SopDefinition toDefinition(SopEntity e) {
        try {
            List<SopDefinition.SopStep> steps = objectMapper.readValue(
                    e.getStepsJson(), new TypeReference<List<SopDefinition.SopStep>>() {});
            List<String> keywords = Arrays.stream(e.getKeywords().split("[,，]"))
                    .map(String::strip).filter(s -> !s.isEmpty()).toList();
            return new SopDefinition(e.getCode(), e.getName(), keywords, steps);
        } catch (Exception ex) {
            log.warn("[SOP] 步骤 JSON 解析失败 code={}: {}", e.getCode(), ex.getMessage());
            return null;
        }
    }

    private SopView toView(SopEntity e) {
        List<SopDefinition.SopStep> steps = List.of();
        try {
            steps = objectMapper.readValue(e.getStepsJson(),
                    new TypeReference<List<SopDefinition.SopStep>>() {});
        } catch (Exception ignored) {
        }
        List<String> keywords = Arrays.stream(e.getKeywords().split("[,，]"))
                .map(String::strip).filter(s -> !s.isEmpty()).toList();
        return new SopView(e.getCode(), e.getName(), keywords, steps,
                e.getEnabled() != null && e.getEnabled() == 1,
                e.getBuiltin() != null && e.getBuiltin() == 1,
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : "");
    }
}
