package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.DigitalEmployeeEntity;
import com.zzp.rag.mysql.mapper.DigitalEmployeeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 数字员工配置（阶段 4 接入运行时；当前为存储层 CRUD）。
 * 降级模式：业务库不可用返回 null，Controller 转 fail 提示。
 * 校验失败抛 IllegalArgumentException，Controller 转友好消息。
 */
@Slf4j
@Service
public class DigitalEmployeeService {

    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final String SCOPE_ALL = "all";

    private final DigitalEmployeeMapper mapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;

    public DigitalEmployeeService(DigitalEmployeeMapper mapper, MysqlStatus status,
                                  ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    public record EmployeeView(long id, String name, String avatar, String description,
                               String systemPrompt, String knowledgeScope, int status,
                               String createdAt, String updatedAt) {}

    public List<EmployeeView> list() {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            return mapper.selectList(new QueryWrapper<DigitalEmployeeEntity>()
                            .orderByDesc("id"))
                    .stream().map(this::toView).toList();
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[数字员工] 列表失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public EmployeeView get(long id) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            DigitalEmployeeEntity entity = mapper.selectById(id);
            return entity != null ? toView(entity) : null;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[数字员工] 查询失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public EmployeeView create(String name, String avatar, String description,
                               String systemPrompt, String knowledgeScope, Boolean statusFlag) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            validateInput(name, knowledgeScope);
            checkNameUnique(name.strip(), 0);
            DigitalEmployeeEntity entity = new DigitalEmployeeEntity();
            entity.setName(name.strip());
            entity.setAvatar(avatar == null || avatar.isBlank() ? "🤖" : avatar.strip());
            entity.setDescription(description);
            entity.setSystemPrompt(systemPrompt);
            entity.setKnowledgeScope(normalizeScope(knowledgeScope));
            entity.setStatus(Boolean.FALSE.equals(statusFlag) ? 0 : 1);
            mapper.insert(entity);
            log.info("[数字员工] 创建 id={} name={}", entity.getId(), entity.getName());
            return toView(mapper.selectById(entity.getId()));   // 回读 DB 默认值（created_at 等）
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[数字员工] 创建失败，降级: {}", e.getMessage());
            return null;
        }
    }

    public EmployeeView update(long id, String name, String avatar, String description,
                               String systemPrompt, String knowledgeScope, Boolean statusFlag) {
        if (!status.isAvailable()) {
            return null;
        }
        try {
            validateInput(name, knowledgeScope);
            checkNameUnique(name.strip(), id);   // 排除自身，允许不改名更新
            DigitalEmployeeEntity entity = mapper.selectById(id);
            if (entity == null) {
                throw new IllegalArgumentException("数字员工不存在");
            }
            entity.setName(name.strip());
            if (avatar != null && !avatar.isBlank()) {
                entity.setAvatar(avatar.strip());
            }
            entity.setDescription(description);
            entity.setSystemPrompt(systemPrompt);
            entity.setKnowledgeScope(normalizeScope(knowledgeScope));
            if (statusFlag != null) {
                entity.setStatus(statusFlag ? 1 : 0);
            }
            mapper.updateById(entity);
            log.info("[数字员工] 更新 id={} name={}", id, entity.getName());
            return toView(mapper.selectById(id));   // 回读 updated_at 等 DB 默认值
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[数字员工] 更新失败，降级: {}", e.getMessage());
            return null;
        }
    }

    /** @return true 删除成功；false 不存在 */
    public boolean delete(long id) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            return mapper.deleteById(id) > 0;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[数字员工] 删除失败，降级: {}", e.getMessage());
            return false;
        }
    }

    /** 纯参数校验：name 非空且长度合法；knowledgeScope 为 all 或 docId UUID 数组 */
    private void validateInput(String name, String knowledgeScope) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("名字不能为空");
        }
        if (name.strip().length() > 64) {
            throw new IllegalArgumentException("名字不能超过 64 字");
        }
        String scope = normalizeScope(knowledgeScope);
        if (!SCOPE_ALL.equals(scope)) {
            try {
                JsonNode node = objectMapper.readTree(scope);
                if (!node.isArray() || node.isEmpty()) {
                    throw new IllegalArgumentException("知识库范围格式错误");
                }
                for (JsonNode item : node) {
                    if (!item.isTextual() || !item.asText().matches("^[0-9a-fA-F\\-]{36}$")) {
                        throw new IllegalArgumentException("知识库范围包含非法文档ID");
                    }
                }
            } catch (IllegalArgumentException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalArgumentException("知识库范围格式错误");
            }
        }
    }

    /** 重名检查（DB 唯一键兜底，这里给友好消息；excludeId>0 时排除自身，支持不改名更新） */
    private void checkNameUnique(String name, long excludeId) {
        QueryWrapper<DigitalEmployeeEntity> qw =
                new QueryWrapper<DigitalEmployeeEntity>().eq("name", name);
        if (excludeId > 0) {
            qw.ne("id", excludeId);
        }
        Long dup = mapper.selectCount(qw);
        if (dup != null && dup > 0) {
            throw new IllegalArgumentException("名字已存在");
        }
    }

    private String normalizeScope(String scope) {
        return scope == null || scope.isBlank() ? SCOPE_ALL : scope.strip();
    }

    private EmployeeView toView(DigitalEmployeeEntity e) {
        return new EmployeeView(
                e.getId(), e.getName(), e.getAvatar(), e.getDescription(), e.getSystemPrompt(),
                e.getKnowledgeScope(), e.getStatus() != null ? e.getStatus() : 0,
                e.getCreatedAt() != null ? e.getCreatedAt().format(TS) : null,
                e.getUpdatedAt() != null ? e.getUpdatedAt().format(TS) : null);
    }
}
