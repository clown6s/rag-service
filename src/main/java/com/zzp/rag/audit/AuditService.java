package com.zzp.rag.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 审计日志服务 — 异步写 JSONL（logs/audit/audit-YYYY-MM-DD.jsonl）
 * <p>
 * 设计取舍：
 * - 异步队列解耦，不阻塞对话链路；写失败只告警，不影响主流程
 * - 队列满丢弃事件并告警（审计优先保链路；后续平台侧聚合时再评估持久化 MQ 方案）
 * - 按天滚动文件，便于归档和平台侧采集（Filebeat/日志采集器/平台聚合服务）
 */
@Slf4j
@Component
public class AuditService {

    private static final int QUEUE_CAPACITY = 10_000;

    private final ObjectMapper objectMapper;
    private final LinkedBlockingQueue<AuditEvent> queue = new LinkedBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean running = new AtomicBoolean(true);
    private Thread writerThread;

    @Value("${rag.audit.enabled:true}")
    private boolean enabled;

    @Value("${rag.audit.log-dir:logs/audit}")
    private String logDir;

    public AuditService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void start() {
        if (!enabled) {
            log.info("[审计] 已禁用 (rag.audit.enabled=false)");
            return;
        }
        writerThread = new Thread(this::writeLoop, "audit-writer");
        writerThread.setDaemon(true);
        writerThread.start();
        log.info("[审计] 启动, 输出目录: {}", logDir);
    }

    /** 非阻塞提交事件 */
    public void record(AuditEvent event) {
        if (!enabled) {
            return;
        }
        if (!queue.offer(event)) {
            log.warn("[审计] 队列已满，丢弃事件 traceId={}", event.traceId());
        }
    }

    private void writeLoop() {
        Path dir = Path.of(logDir);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            log.error("[审计] 创建日志目录失败: {}", logDir, e);
            return;
        }

        BufferedWriter writer = null;
        String currentDate = "";
        while (running.get() || !queue.isEmpty()) {
            AuditEvent event;
            try {
                event = queue.poll(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (event == null) {
                continue;
            }

            String today = LocalDate.now().toString();
            if (!today.equals(currentDate)) {
                closeQuietly(writer);
                writer = null;
                try {
                    writer = Files.newBufferedWriter(
                            dir.resolve("audit-" + today + ".jsonl"),
                            StandardCharsets.UTF_8,
                            StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                    currentDate = today;
                } catch (IOException e) {
                    log.error("[审计] 打开日志文件失败，事件丢弃 traceId={}: {}", event.traceId(), e.getMessage());
                    continue;
                }
            }
            try {
                writer.write(objectMapper.writeValueAsString(event));
                writer.newLine();
                writer.flush(); // 审计要求耐久性优先；量级低（对话请求），性能可接受
            } catch (Exception e) {
                log.error("[审计] 写事件失败 traceId={}: {}", event.traceId(), e.getMessage());
            }
        }
        closeQuietly(writer);
        log.info("[审计] 写入线程退出");
    }

    private void closeQuietly(BufferedWriter writer) {
        if (writer == null) {
            return;
        }
        try {
            writer.close();
        } catch (IOException e) {
            log.warn("[审计] 关闭日志文件失败: {}", e.getMessage());
        }
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        if (writerThread != null) {
            try {
                writerThread.join(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
