package com.zzp.rag.mysql.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.DocAclEntity;
import com.zzp.rag.mysql.mapper.DocAclMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 文档级访问控制：doc → 授权员工列表（["employee:2", ...]）。
 * 未建记录的文档 = 全员可见（向后兼容）；建了记录且列表非空 = 仅授权员工可见。
 * <p>
 * allowedDocIds 结果缓存 60s（检索每问都会调；授权变更 1 分钟内生效，可接受）。
 * 软依赖：业务库不可用时返回空集——不产生任何 ACL 限制（授权过宽优于服务不可用）。
 */
@Slf4j
@Service
public class DocAclService {

    public record AclView(String docId, List<String> grants, String updatedBy, String updatedAt) {}

    private final DocAclMapper mapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;

    private final ConcurrentHashMap<Long, Cached> allowedCache = new ConcurrentHashMap<>();
    private record Cached(List<String> docIds, long expireAt) {}
    private final AtomicLong cacheClock = new AtomicLong(0);

    public DocAclService(DocAclMapper mapper, MysqlStatus status, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.status = status;
        this.objectMapper = objectMapper;
    }

    /** 设置文档授权（grants 为 employee id 列表；空列表 = 恢复全员可见） */
    public boolean setAcl(String docId, List<Long> employeeIds, String operator) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            DocAclEntity e = mapper.selectOne(new QueryWrapper<DocAclEntity>().eq("doc_id", docId));
            String grantsJson = objectMapper.writeValueAsString(
                    employeeIds == null ? List.of() : employeeIds);
            if (e == null) {
                e = new DocAclEntity();
                e.setDocId(docId);
                e.setGrants(grantsJson);
                e.setUpdatedBy(operator);
                mapper.insert(e);
            } else {
                e.setGrants(grantsJson);
                e.setUpdatedBy(operator);
                mapper.updateById(e);
            }
            allowedCache.clear();
            log.info("[文档ACL] 设置 doc={} grants={} by={}", docId, grantsJson, operator);
            return true;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            log.warn("[文档ACL] 设置失败，降级: {}", ex.getMessage());
            return false;
        }
    }

    /** 全部 ACL 记录（管理页列表） */
    public List<AclView> listAcls() {
        if (!status.isAvailable()) {
            return List.of();
        }
        try {
            return mapper.selectList(new QueryWrapper<DocAclEntity>().orderByDesc("updated_at"))
                    .stream().map(this::toView).toList();
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return List.of();
        }
    }

    /** 指定员工可见的受限文档 id 集合（缓存 60s；业务库不可用返回空集 = 不限制） */
    public List<String> restrictedDocIds(Long employeeId) {
        if (!status.isAvailable() || employeeId == null) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        Cached cached = allowedCache.get(employeeId);
        if (cached != null && now < cached.expireAt()) {
            return cached.docIds();
        }
        try {
            String grant = "\"employee:" + employeeId + "\"";
            List<DocAclEntity> rows = mapper.selectList(new QueryWrapper<DocAclEntity>());
            List<String> allowed = new ArrayList<>();
            for (DocAclEntity e : rows) {
                if (e.getGrants() != null && e.getGrants().contains(grant)) {
                    allowed.add(e.getDocId());
                }
            }
            allowedCache.put(employeeId, new Cached(allowed, now + 60_000));
            return allowed;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            log.warn("[文档ACL] 授权解析失败，按无限制降级: {}", ex.getMessage());
            return List.of();
        }
    }

    /**
     * 授权范围外文档 id 全集（用于 Milvus 过滤表达式：document_id not in [...]）。
     * 空 = 无任何文档被限制。员工能看到：无 ACL 记录的文档 + 授权列表包含该员工的文档；
     * 即应排除：有 ACL 记录且未授权该员工的文档。
     */
    public List<String> excludedDocIds(Long employeeId) {
        if (!status.isAvailable() || employeeId == null) {
            return List.of();
        }
        try {
            String grant = "\"employee:" + employeeId + "\"";
            List<DocAclEntity> rows = mapper.selectList(new QueryWrapper<DocAclEntity>());
            List<String> excluded = new ArrayList<>();
            for (DocAclEntity e : rows) {
                boolean hasGrant = e.getGrants() != null && e.getGrants().contains(grant);
                boolean emptyGrant = e.getGrants() == null || e.getGrants().isBlank() || "[]".equals(e.getGrants().strip());
                if (!hasGrant && !emptyGrant) {
                    excluded.add(e.getDocId());
                }
            }
            return excluded;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return List.of();
        }
    }

    /** 移除文档 ACL（恢复全员可见） */
    public boolean removeAcl(String docId) {
        if (!status.isAvailable()) {
            return false;
        }
        try {
            int n = mapper.delete(new QueryWrapper<DocAclEntity>().eq("doc_id", docId));
            allowedCache.clear();
            return n > 0;
        } catch (Exception ex) {
            status.markUnavailable(ex.getMessage());
            return false;
        }
    }

    private AclView toView(DocAclEntity e) {
        List<String> grants = List.of();
        if (e.getGrants() != null && !e.getGrants().isBlank()) {
            try {
                List<Long> ids = objectMapper.readValue(e.getGrants(), new TypeReference<List<Long>>() {});
                grants = ids.stream().map(id -> "employee:" + id).toList();
            } catch (Exception ignored) {
            }
        }
        return new AclView(e.getDocId(), grants, e.getUpdatedBy(),
                e.getUpdatedAt() != null ? e.getUpdatedAt().toString() : "");
    }
}
