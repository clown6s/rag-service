package com.zzp.rag.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.mysql.MysqlStatus;
import com.zzp.rag.mysql.entity.AuditEventEntity;
import com.zzp.rag.mysql.mapper.AuditEventMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 审计聚合器：JSONL（唯一真相源）→ MySQL 幂等 upsert。
 * <p>
 * 游标策略：进程内内存游标（文件名→已读行数），失败批次游标不前进（下 tick 原样重试）；
 * 重启游标归零 → 重扫最近 N 天文件，靠 trace_id 唯一键幂等补漏，不持久化偏移文件
 * （偏移写入与入库非原子，崩溃窗口丢半批更难恢复）。
 */
@Slf4j
@Component
public class AuditAggregator {

    private static final Pattern FILE_PATTERN =
            Pattern.compile("audit-(\\d{4}-\\d{2}-\\d{2})\\.jsonl");
    private static final DateTimeFormatter FILE_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final int BATCH_SIZE = 500;

    private final AuditEventMapper mapper;
    private final MysqlStatus status;
    private final ObjectMapper objectMapper;
    private final javax.sql.DataSource dataSource;
    private final com.zzp.rag.mysql.MysqlSchemaInitializer schemaInitializer;

    @Value("${rag.mysql.enabled:true}")
    private boolean enabled;

    @Value("${rag.audit.enabled:true}")
    private boolean auditEnabled;

    @Value("${rag.audit.log-dir:logs/audit}")
    private String logDir;

    @Value("${rag.mysql.audit-rescan-days:3}")
    private int rescanDays;

    /** 内存游标：文件名 → 已入库行数（重启归零，靠幂等补漏） */
    private final Map<String, Integer> cursors = new ConcurrentHashMap<>();

    public AuditAggregator(AuditEventMapper mapper, MysqlStatus status, ObjectMapper objectMapper,
                           javax.sql.DataSource dataSource,
                           com.zzp.rag.mysql.MysqlSchemaInitializer schemaInitializer) {
        this.mapper = mapper;
        this.status = status;
        this.objectMapper = objectMapper;
        this.dataSource = dataSource;
        this.schemaInitializer = schemaInitializer;
    }

    @Scheduled(fixedDelayString = "${rag.mysql.audit-aggregate-interval-ms:10000}",
            initialDelay = 60000)
    public void aggregate() {
        if (!enabled || !auditEnabled) {
            return;
        }
        if (!status.isAvailable()) {
            boolean wasUnavailable = true;
            probe();   // 启动探针失败后每 tick 重探，MySQL 恢复即自愈（无需重启服务）
            if (!status.isAvailable()) {
                return;
            }
            // 恢复后补建表（启动时 MySQL 不在线，启动建表被跳过）
            if (wasUnavailable) {
                schemaInitializer.ensureSchema();
            }
        }
        try {
            int total = 0;
            for (File file : recentAuditFiles()) {
                total += aggregateFile(file);
            }
            if (total > 0) {
                log.info("[审计聚合] 入库 {} 条", total);
            }
        } catch (Exception e) {
            // 单批失败置不可用，游标不前进 → 下 tick（MySQL 自愈后）原样重试
            status.markUnavailable(e.getMessage());
            log.warn("[审计聚合] 失败，游标保持待重试: {}", e.getMessage());
        }
    }

    /** 轻量重探：仅尝试取连接，成功即恢复可用；失败原因变化时打 WARN（可见性，不刷屏） */
    private String lastProbeError = "";

    private void probe() {
        try (java.sql.Connection conn = dataSource.getConnection()) {
            status.markAvailable();
        } catch (Exception e) {
            String msg = e.getMessage() == null ? "unknown" : e.getMessage();
            if (!msg.equals(lastProbeError)) {
                lastProbeError = msg;
                log.warn("[审计聚合] 探针失败: {}", msg);
            }
        }
    }

    /** 最近 N 天（含当天）的审计文件，按日期升序 */
    private List<File> recentAuditFiles() {
        LocalDate cutoff = LocalDate.now().minusDays(rescanDays - 1);
        File dir = new File(logDir);
        File[] files = dir.listFiles();
        if (files == null) {
            return List.of();
        }
        List<File> result = new ArrayList<>();
        for (File f : files) {
            Matcher m = FILE_PATTERN.matcher(f.getName());
            if (!m.matches()) {
                continue;
            }
            try {
                if (!LocalDate.parse(m.group(1), FILE_DATE).isBefore(cutoff)) {
                    result.add(f);
                }
            } catch (Exception ignored) {
                // 文件名日期解析失败跳过
            }
        }
        result.sort(Comparator.comparing(File::getName));
        return result;
    }

    /** 从游标续读单文件并批量入库；返回本 tick 该文件入库条数 */
    private int aggregateFile(File file) {
        List<String> lines;
        try {
            lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("[审计聚合] 读取文件失败 {}: {}", file.getName(), e.getMessage());
            return 0;
        }
        int start = cursors.getOrDefault(file.getName(), 0);
        if (start >= lines.size()) {
            return 0;
        }
        List<AuditEventEntity> batch = new ArrayList<>();
        int inserted = 0;
        for (int i = start; i < lines.size(); i++) {
            AuditEventEntity entity = parseLine(lines.get(i));
            if (entity == null) {
                continue;   // 单行损坏跳过，不中断批次
            }
            batch.add(entity);
            if (batch.size() >= BATCH_SIZE) {
                mapper.batchUpsert(batch);
                inserted += batch.size();
                batch.clear();
            }
        }
        if (!batch.isEmpty()) {
            mapper.batchUpsert(batch);
            inserted += batch.size();
        }
        cursors.put(file.getName(), lines.size());
        return inserted;
    }

    /** JSONL 行 → 实体；traceId 为 "-"（MDC 缺失占位）或空跳过（唯一键会互相覆盖） */
    private AuditEventEntity parseLine(String line) {
        try {
            AuditEvent event = objectMapper.readValue(line, AuditEvent.class);
            if (event.traceId() == null || event.traceId().isBlank() || "-".equals(event.traceId())) {
                return null;
            }
            AuditEventEntity e = new AuditEventEntity();
            e.setTraceId(event.traceId());
            e.setTimestamp(event.timestamp());
            e.setUser(event.user());
            e.setIp(event.ip());
            e.setEndpoint(event.endpoint());
            e.setCategory(event.category());
            e.setQuery(event.query());
            e.setHistoryCount(event.historyCount());
            e.setSources(event.sources() == null ? null
                    : objectMapper.writeValueAsString(event.sources()));
            e.setRetrievalMs(event.retrievalMs());
            e.setRerankDegraded(event.rerankDegraded());
            e.setGraphContextChars(event.graphContextChars());
            e.setAnswerPreview(event.answerPreview());
            e.setAnswerLength(event.answerLength());
            e.setTotalMs(event.totalMs());
            e.setStatus(event.status());
            e.setError(event.error());
            e.setEmployeeId(event.employeeId());
            return e;
        } catch (Exception e) {
            log.warn("[审计聚合] 行解析失败，跳过: {}", line.length() > 80 ? line.substring(0, 80) : line);
            return null;
        }
    }
}
