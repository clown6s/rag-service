package com.zzp.rag.mysql;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 启动建表：执行 classpath:schema-mysql.sql（CREATE TABLE IF NOT EXISTS，幂等）。
 * 业务库不可用时静默跳过（软依赖：无 MySQL 也能启动，聚合器后续自愈后补建）。
 */
@Slf4j
@Component
public class MysqlSchemaInitializer implements ApplicationRunner {

    private final DataSource dataSource;
    private final MysqlStatus status;

    @Value("${rag.mysql.enabled:true}")
    private boolean enabled;

    public MysqlSchemaInitializer(DataSource dataSource, MysqlStatus status) {
        this.dataSource = dataSource;
        this.status = status;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled || !status.isAvailable()) {
            log.info("[MySQL] 建表跳过（业务库未启用或不可用）");
            return;
        }
        ensureSchema();
    }

    /**
     * 幂等建表（CREATE TABLE IF NOT EXISTS）。
     * 启动时执行一次；聚合器探针恢复可用性后也会补调（启动时 MySQL 不在线的场景）。
     * @return 是否成功
     */
    public boolean ensureSchema() {
        try {
            String sql = new String(new ClassPathResource("schema-mysql.sql")
                    .getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            try (Connection conn = dataSource.getConnection(); Statement stmt = conn.createStatement()) {
                for (String part : sql.split(";")) {
                    if (part == null || part.isBlank() || part.strip().startsWith("--")) {
                        continue;
                    }
                    try {
                        stmt.execute(part);
                    } catch (Exception e) {
                        // 单条失败不中断（一张表失败不影响另一张），只告警
                        log.warn("[MySQL] 建表语句执行失败: {}", e.getMessage());
                    }
                }
            }
            log.info("[MySQL] 建表检查完成（audit_event / digital_employee）");
            return true;
        } catch (Exception e) {
            status.markUnavailable(e.getMessage());
            log.warn("[MySQL] 建表失败，跳过: {}", e.getMessage());
            return false;
        }
    }
}
