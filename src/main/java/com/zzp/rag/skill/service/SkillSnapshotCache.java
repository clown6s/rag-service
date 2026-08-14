package com.zzp.rag.skill.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 授权快照缓存：ToolProvider 每次对话读取的 (默认员工, 已授权技能) 快照。
 * TTL 5 秒兜底 MySQL 抖动；管理端写操作显式 invalidate 即时生效。
 * <p>
 * 快照内容与内置兜底分离：快照加载失败（MySQL 不可用）时返回 null，
 * 由 BuiltinSkillFallback 兜底装配内置技能（软依赖）。
 */
@Slf4j
@Component
public class SkillSnapshotCache {

    public record AuthorizedSkill(long id, String code, String name, String toolType,
                                  String mcpConfig, String toolNames, int status) {}

    public record Snapshot(long defaultEmployeeId, List<AuthorizedSkill> skills) {}

    private static final long TTL_MS = 5_000;

    private final AtomicReference<Snapshot> snapshot = new AtomicReference<>();
    private volatile long loadedAt = 0;

    public Snapshot getOrLoad(java.util.function.Supplier<Snapshot> loader) {
        long now = System.currentTimeMillis();
        Snapshot current = snapshot.get();
        if (current != null && now - loadedAt < TTL_MS) {
            return current;
        }
        Snapshot fresh = loader.get();
        if (fresh != null) {
            snapshot.set(fresh);
            loadedAt = now;
        }
        return fresh != null ? fresh : current;   // 加载失败回退旧快照
    }

    /** 管理端写操作后调用：下一轮 provideTools 立即重载 */
    public void invalidate() {
        snapshot.set(null);
        loadedAt = 0;
        log.debug("[技能] 授权快照已失效");
    }
}
