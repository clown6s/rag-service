package com.zzp.rag.mysql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 业务库可用性状态（软依赖核心）：
 * 启动探针置初始状态；Service 层调用前检查走快速降级路径，
 * 触库异常时置为不可用——Hikari 连接池在 MySQL 恢复后自动重建，
 * 下次调用成功即回可用，运行时故障自愈无需重启。
 */
@Slf4j
@Component
public class MysqlStatus {

    private final AtomicBoolean available = new AtomicBoolean(false);
    private volatile String lastError = "未初始化";

    public boolean isAvailable() {
        return available.get();
    }

    public String getLastError() {
        return lastError;
    }

    public void markAvailable() {
        if (available.compareAndSet(false, true)) {
            log.info("[MySQL] 业务库已连接");
        }
    }

    public void markUnavailable(String error) {
        lastError = error;
        if (available.compareAndSet(true, false)) {
            log.warn("[MySQL] 业务库不可用，降级: {}", error);
        }
    }
}
