package com.zzp.rag.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 模型用量追踪（成本看板）：按 (日期, 模型, 用途) 聚合调用次数/字符量/耗时。
 * token 数为字符量换算的估算值（中文约 1 token ≈ 1.2 字符），看板标注"估算"。
 * 内存聚合 + 每日快照落盘 data/usage/{date}.json（重启不丢当天前累计，软依赖）。
 */
@Slf4j
@Component
public class UsageTracker {

    public record ModelUsage(String model, String purpose, long calls, long chars,
                             long estTokens, long totalMs) {}

    public record DailyUsage(String day, long calls, long estTokens, long totalMs,
                             List<ModelUsage> byModel) {}

    private static final double CHARS_PER_TOKEN = 1.2;

    private final ObjectMapper objectMapper;
    private final Path dir = Path.of("data", "usage");

    /** key = date:model:purpose */
    private final ConcurrentHashMap<String, Counter> counters = new ConcurrentHashMap<>();

    private record Counter(AtomicLong calls, AtomicLong chars, AtomicLong ms) {}

    public UsageTracker(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 记录一次模型调用（编排器各调用点埋桩） */
    public void record(String model, String purpose, int inputChars, int outputChars, long durationMs) {
        try {
            String day = LocalDate.now().toString();
            Counter c = counters.computeIfAbsent(day + ":" + model + ":" + purpose,
                    k -> new Counter(new AtomicLong(), new AtomicLong(), new AtomicLong()));
            c.calls().incrementAndGet();
            c.chars().addAndGet((long) inputChars + outputChars);
            c.ms().addAndGet(durationMs);
        } catch (Exception e) {
            log.warn("[用量] 记录失败: {}", e.getMessage());
        }
    }

    /** 近 N 天用量（内存聚合 + 落盘快照合并；业务无关，永不失败） */
    public List<DailyUsage> usage(int days) {
        try {
            List<DailyUsage> out = new ArrayList<>();
            for (int i = 0; i < days; i++) {
                String day = LocalDate.now().minusDays(i).toString();
                // 内存聚合
                Map<String, Counter> dayCounters = new ConcurrentHashMap<>();
                counters.forEach((k, v) -> {
                    if (k.startsWith(day + ":")) {
                        String[] parts = k.split(":", 3);
                        Counter target = dayCounters.computeIfAbsent(parts[1] + ":" + parts[2],
                                x -> new Counter(new AtomicLong(), new AtomicLong(), new AtomicLong()));
                        target.calls().addAndGet(v.calls().get());
                        target.chars().addAndGet(v.chars().get());
                        target.ms().addAndGet(v.ms().get());
                    }
                });
                // 落盘快照合并（重启后当天早前数据）
                Path file = dir.resolve(day + ".json");
                if (Files.exists(file)) {
                    JsonSnapshot snap = objectMapper.readValue(Files.readString(file), JsonSnapshot.class);
                    for (JsonEntry e : snap.entries()) {
                        Counter merged = dayCounters.computeIfAbsent(e.model() + ":" + e.purpose(),
                                x -> new Counter(new AtomicLong(), new AtomicLong(), new AtomicLong()));
                        merged.calls().addAndGet(e.calls());
                        merged.chars().addAndGet(e.chars());
                        merged.ms().addAndGet(e.ms());
                    }
                }
                List<ModelUsage> byModel = new ArrayList<>();
                long dayCalls = 0, dayTokens = 0, dayMs = 0;
                for (Map.Entry<String, Counter> e : dayCounters.entrySet()) {
                    String[] mp = e.getKey().split(":", 2);
                    Counter c = e.getValue();
                    long calls = c.calls().get();
                    long chars = c.chars().get();
                    long estTokens = Math.round(chars / CHARS_PER_TOKEN);
                    long ms = c.ms().get();
                    byModel.add(new ModelUsage(mp[0], mp[1], calls, chars, estTokens, ms));
                    dayCalls += calls;
                    dayTokens += estTokens;
                    dayMs += ms;
                }
                byModel.sort((a, b) -> Long.compare(b.calls(), a.calls()));
                out.add(new DailyUsage(day, dayCalls, dayTokens, dayMs, byModel));
            }
            return out;
        } catch (Exception e) {
            log.warn("[用量] 统计失败: {}", e.getMessage());
            return List.of();
        }
    }

    /** 每 10 分钟快照落盘（进程退出丢最多 10 分钟增量，软依赖） */
    @org.springframework.scheduling.annotation.Scheduled(fixedRate = 600_000)
    public synchronized void snapshotToday() {
        try {
            Files.createDirectories(dir);
            String day = LocalDate.now().toString();
            List<JsonEntry> entries = new ArrayList<>();
            counters.forEach((k, v) -> {
                if (k.startsWith(day + ":")) {
                    String[] parts = k.split(":", 3);
                    entries.add(new JsonEntry(parts[1], parts[2], v.calls().get(), v.chars().get(), v.ms().get()));
                }
            });
            Files.writeString(dir.resolve(day + ".json"),
                    objectMapper.writeValueAsString(new JsonSnapshot(entries)));
        } catch (Exception e) {
            log.warn("[用量] 快照落盘失败: {}", e.getMessage());
        }
    }

    private record JsonEntry(String model, String purpose, long calls, long chars, long ms) {}
    private record JsonSnapshot(List<JsonEntry> entries) {}
}
