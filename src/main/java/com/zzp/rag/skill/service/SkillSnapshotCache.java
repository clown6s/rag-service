package com.zzp.rag.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * 授权快照缓存：ToolProvider 每次对话读取 (员工, 已授权技能) 快照。
 * TTL 5 秒兜底 MySQL 抖动；管理端写操作显式 invalidate 即时生效。
 * <p>
 * 阶段 4 起按 employeeId 分键：每个数字员工一份快照（请求带 agentId 时按请求员工装配）。
 * 快照内容与内置兜底分离：快照加载失败（MySQL 不可用）时返回 null，
 * 由 BuiltinSkillFallback 兜底装配内置技能（软依赖）。
 */
@Slf4j
@Component
public class SkillSnapshotCache {

    public record AuthorizedSkill(long id, String code, String name, String toolType,
                                  String mcpConfig, String toolNames, int status) {}

    public record Snapshot(long employeeId, List<AuthorizedSkill> skills) {}

    private static final long TTL_MS = 5_000;

    private record Entry(Snapshot snapshot, long loadedAt) {}

    private final ConcurrentHashMap<Long, Entry> cache = new ConcurrentHashMap<>();

    public Snapshot getOrLoad(long employeeId, Supplier<Snapshot> loader) {
        long now = System.currentTimeMillis();
        Entry entry = cache.get(employeeId);
        if (entry != null && now - entry.loadedAt < TTL_MS) {
            return entry.snapshot;
        }
        Snapshot fresh = loader.get();
        if (fresh != null) {
            cache.put(employeeId, new Entry(fresh, now));
            return fresh;
        }
        return entry != null ? entry.snapshot : null;   // 加载失败回退旧快照
    }

    /** 管理端写操作后调用：下一轮 provideTools 全部重载 */
    public void invalidate() {
        cache.clear();
        log.debug("[技能] 授权快照已失效");
    }
}
