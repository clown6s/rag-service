package com.zzp.rag.skill.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.skill.entity.SkillEmployeeEntity;
import com.zzp.rag.skill.entity.SkillEntity;
import com.zzp.rag.skill.mapper.SkillEmployeeMapper;
import com.zzp.rag.skill.mapper.SkillMapper;
import com.zzp.rag.mysql.entity.DigitalEmployeeEntity;
import com.zzp.rag.mysql.mapper.DigitalEmployeeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 技能注册中心（降级模式沿袭 DigitalEmployeeService：不可用返回 null）。
 * 写操作后失效授权快照缓存（管理端操作即时生效）。
 */
@Slf4j
@Service
public class SkillService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final SkillMapper skillMapper;
    private final SkillEmployeeMapper skillEmployeeMapper;
    private final DigitalEmployeeMapper employeeMapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;
    private final SkillSnapshotCache snapshotCache;
    private final com.zzp.rag.mcp.server.McpServerRegistry mcpRegistry;

    @Value("${rag.skill.default-employee-id:0}")
    private long configuredDefaultEmployeeId;

    public SkillService(SkillMapper skillMapper, SkillEmployeeMapper skillEmployeeMapper,
                        DigitalEmployeeMapper employeeMapper, MysqlStatus status,
                        ObjectMapper objectMapper, SkillSnapshotCache snapshotCache,
                        com.zzp.rag.mcp.server.McpServerRegistry mcpRegistry) {
        this.skillMapper = skillMapper;
        this.skillEmployeeMapper = skillEmployeeMapper;
        this.employeeMapper = employeeMapper;
        this.status = status;
        this.objectMapper = objectMapper;
        this.snapshotCache = snapshotCache;
        this.mcpRegistry = mcpRegistry;
    }

    public record SkillView(long id, String code, String name, String description, String skillMd,
                            String toolType, String mcpConfig, String toolNames, int status,
                            long employeeCount, String createdAt, String updatedAt) {}

    // ==================== 技能 CRUD ====================

    public List<SkillView> list() {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            return skillMapper.selectList(new QueryWrapper<SkillEntity>().orderByAsc("id"))
                    .stream().map(this::toView).toList();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 列表失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public SkillView get(long id) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            SkillEntity entity = skillMapper.selectById(id);
            return entity != null ? toView(entity) : null;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 查询失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public SkillView create(String code, String name, String description, String skillMd,
                            String toolType, String mcpConfig, String toolNames, Boolean statusFlag) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            validate(code, name, toolType, mcpConfig, 0);
            SkillEntity entity = new SkillEntity();
            entity.setCode(code.strip());
            entity.setName(name.strip());
            entity.setDescription(description);
            entity.setSkillMd(skillMd);
            entity.setToolType(toolType);
            entity.setMcpConfig(toolType.equals("mcp") ? mcpConfig.strip() : null);
            entity.setToolNames(toolNames);
            entity.setStatus(Boolean.FALSE.equals(statusFlag) ? 0 : 1);
            skillMapper.insert(entity);
            snapshotCache.invalidate();
            log.info("[技能] 创建 id={} code={}", entity.getId(), entity.getCode());
            return toView(skillMapper.selectById(entity.getId()));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 创建失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public SkillView update(long id, String name, String description, String skillMd,
                            String mcpConfig, String toolNames, Boolean statusFlag) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            SkillEntity entity = skillMapper.selectById(id);
            if (entity == null) {
                throw new IllegalArgumentException("技能不存在");
            }
            // code 与 toolType 不可改（注册即定身份）
            validate(entity.getCode(), name, entity.getToolType(), mcpConfig, id);
            entity.setName(name.strip());
            entity.setDescription(description);
            entity.setSkillMd(skillMd);
            if (entity.getToolType().equals("mcp")) {
                entity.setMcpConfig(mcpConfig != null ? mcpConfig.strip() : null);
            }
            entity.setToolNames(toolNames);
            if (statusFlag != null) {
                entity.setStatus(statusFlag ? 1 : 0);
            }
            skillMapper.updateById(entity);
            snapshotCache.invalidate();
            mcpRegistry.invalidate(entity.getCode());   // 配置变更 → 丢旧连接，下次按新配置重连
            log.info("[技能] 更新 id={} code={}", id, entity.getCode());
            return toView(skillMapper.selectById(id));
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 更新失败，降级: {}", e.getMessage());
            return null;
        }
    }

    /** @return true 删除成功；false 不存在 */
    public boolean delete(long id) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            SkillEntity target = skillMapper.selectById(id);
            int deleted = skillMapper.deleteById(id);
            if (deleted > 0) {
                // 级联清理授权（无外键，手动删）+ 丢弃连接句柄
                skillEmployeeMapper.delete(new QueryWrapper<SkillEmployeeEntity>()
                        .eq("skill_id", id));
                snapshotCache.invalidate();
                if (target != null) {
                    mcpRegistry.invalidate(target.getCode());
                }
                log.info("[技能] 删除 id={}（含授权）", id);
            }
            return deleted > 0;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 删除失败，降级: {}", e.getMessage());
            return false;
        }
    }

    // ==================== 员工-技能授权 ====================

    public List<SkillView> getEmployeeSkills(long employeeId) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            List<Long> ids = skillEmployeeMapper.findSkillIdsByEmployee(employeeId);
            if (ids.isEmpty()) {
                return List.of();
            }
            return skillMapper.selectBatchIds(ids).stream().map(this::toView).toList();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 员工技能查询失败，降级: {}", e.getMessage());
            return null;
        }
    }

    /** 全量替换授权（覆盖语义，幂等） */
    public List<SkillView> replaceEmployeeSkills(long employeeId, List<Long> skillIds) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            skillEmployeeMapper.revokeAll(employeeId);
            if (skillIds != null) {
                for (Long skillId : skillIds.stream().distinct().toList()) {
                    if (skillMapper.selectById(skillId) != null) {
                        skillEmployeeMapper.grant(employeeId, skillId);
                    }
                }
            }
            snapshotCache.invalidate();
            log.info("[技能] 员工 {} 授权替换为 {} 项", employeeId, skillIds != null ? skillIds.size() : 0);
            return getEmployeeSkills(employeeId);
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[技能] 授权替换失败，降级: {}", e.getMessage());
            return null;
        }
    }

    // ==================== 默认员工 ====================

    /**
     * 解析默认数字员工（ToolProvider 装配授权快照用）：
     * rag.skill.default-employee-id 配置优先 → 否则取 status=1 的最小 id 员工 → 无则 -1（只装配内置兜底）
     */
    public long resolveDefaultEmployeeId() {
        if (!status.isAvailable()) {
            return -1;
        }
        try {
            if (configuredDefaultEmployeeId > 0) {
                return configuredDefaultEmployeeId;
            }
            DigitalEmployeeEntity first = employeeMapper.selectOne(
                    new QueryWrapper<DigitalEmployeeEntity>().eq("status", 1).orderByAsc("id").last("LIMIT 1"));
            return first != null ? first.getId() : -1;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            return -1;
        }
    }

    // ==================== 校验与转换 ====================

    private void validate(String code, String name, String toolType, String mcpConfig, long excludeId) {
        if (code == null || !code.matches("^[a-z][a-z0-9_]{2,63}$")) {
            throw new IllegalArgumentException("技能编码格式错误（小写字母开头，3-64 位字母数字下划线）");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("技能名称不能为空");
        }
        if (!"builtin".equals(toolType) && !"mcp".equals(toolType)) {
            throw new IllegalArgumentException("技能类型必须是 builtin 或 mcp");
        }
        if ("mcp".equals(toolType)) {
            if (mcpConfig == null || mcpConfig.isBlank()) {
                throw new IllegalArgumentException("MCP 技能必须配置连接信息");
            }
            try {
                JsonNode node = objectMapper.readTree(mcpConfig);
                if (!node.has("baseUrl") || !node.get("baseUrl").asText().startsWith("http")) {
                    throw new IllegalArgumentException("mcpConfig 必须包含合法的 baseUrl");
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("mcpConfig 不是合法 JSON");
            }
        }
        // code 唯一（DB 唯一键兜底，这里给友好消息）
        QueryWrapper<SkillEntity> qw = new QueryWrapper<SkillEntity>().eq("code", code);
        if (excludeId > 0) {
            qw.ne("id", excludeId);
        }
        Long dup = skillMapper.selectCount(qw);
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("技能编码已存在");
        }
    }

    private SkillView toView(SkillEntity e) {
        return new SkillView(
                e.getId(), e.getCode(), e.getName(), e.getDescription(), e.getSkillMd(),
                e.getToolType(), e.getMcpConfig(), e.getToolNames(),
                e.getStatus() != null ? e.getStatus() : 0,
                skillEmployeeMapper.countEmployeesBySkill(e.getId()),
                e.getCreatedAt() != null ? e.getCreatedAt().format(TS) : null,
                e.getUpdatedAt() != null ? e.getUpdatedAt().format(TS) : null);
    }
}
