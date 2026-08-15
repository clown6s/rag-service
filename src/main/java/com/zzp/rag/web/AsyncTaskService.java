package com.zzp.rag.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * 通用长任务异步执行框架：提交即返回 taskId，轮询 /tasks/{id} 查状态与结果。
 * <p>
 * 状态持久化到 data/async-tasks/{id}.json（服务重启后仍可查历史任务）；
 * 工作线程池 2 线程（长任务串行排队，防资源打爆）；失败不吞异常，error 透出。
 */
@Slf4j
@Service
public class AsyncTaskService {

    public record TaskView(String id, String name, String status, String progress,
                           long startedAt, long finishedAt, Object result, String error) {}

    private static final Map<String, TaskView> TASKS = new ConcurrentHashMap<>();
    private static final AtomicInteger SEQ = new AtomicInteger(0);
    private final ExecutorService pool = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "async-task");
        t.setDaemon(true);
        return t;
    });
    private final ObjectMapper objectMapper;
    private final Path dir = Path.of("data", "async-tasks");

    public AsyncTaskService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 提交长任务：work 可调用 setProgress 汇报进度（线程安全的局部变量） */
    public String submit(String name, java.util.function.Consumer<ProgressUpdater> work) {
        String id = UUID.randomUUID().toString().substring(0, 8);
        TaskView task = new TaskView(id, name, "RUNNING", "排队中…",
                System.currentTimeMillis(), 0, null, null);
        TASKS.put(id, task);
        persist(task);
        pool.submit(() -> runTask(id, name, work));
        log.info("[异步任务] 提交 {} id={}", name, id);
        return id;
    }

    /** 进度汇报句柄（worker 线程内调用） */
    public interface ProgressUpdater {
        void progress(String message);
    }

    private void runTask(String id, String name, java.util.function.Consumer<ProgressUpdater> work) {
        long start = System.currentTimeMillis();
        ProgressUpdater updater = msg -> {
            TaskView cur = TASKS.get(id);
            if (cur != null) {
                TASKS.put(id, new TaskView(id, name, "RUNNING", msg,
                        cur.startedAt(), 0, null, null));
                persist(TASKS.get(id));
            }
        };
        try {
            work.accept(updater);
            Object result = TASKS.get(id) != null ? TASKS.get(id).result() : null;   // setResult 先于完成标记落盘
            TaskView done = new TaskView(id, name, "SUCCESS", "完成",
                    start, System.currentTimeMillis(), result, null);
            TASKS.put(id, done);
            persist(done);
            log.info("[异步任务] 完成 {} id={} 耗时 {}ms", name, id, System.currentTimeMillis() - start);
        } catch (Exception e) {
            TaskView failed = new TaskView(id, name, "FAILED", "失败",
                    start, System.currentTimeMillis(), null, e.getMessage());
            TASKS.put(id, failed);
            persist(failed);
            log.error("[异步任务] 失败 {} id={}: {}", name, id, e.getMessage());
        }
    }

    /** 按 id 查任务状态；未知 id 返回 null */
    public TaskView get(String id) {
        return TASKS.get(id);
    }

    /** 最近任务列表（倒序） */
    public List<TaskView> list() {
        List<TaskView> all = new ArrayList<>(TASKS.values());
        all.sort(Comparator.comparingLong(TaskView::startedAt).reversed());
        return all.stream().limit(20).toList();
    }

    /** 设置任务结果（worker 内部成功后调用，如评测报告） */
    public void setResult(String id, Object result) {
        TaskView cur = TASKS.get(id);
        if (cur != null) {
            TASKS.put(id, new TaskView(id, cur.name(), cur.status(), cur.progress(),
                    cur.startedAt(), cur.finishedAt(), result, cur.error()));
            persist(TASKS.get(id));
        }
    }

    private void persist(TaskView task) {
        try {
            Files.createDirectories(dir);
            Map<String, Object> slim = Map.of(
                    "id", task.id(), "name", task.name(), "status", task.status(),
                    "progress", task.progress(), "startedAt", task.startedAt(),
                    "finishedAt", task.finishedAt(), "result", task.result() == null ? "" : task.result(),
                    "error", task.error() == null ? "" : task.error());
            Files.writeString(dir.resolve(task.id() + ".json"),
                    objectMapper.writeValueAsString(slim));
        } catch (Exception e) {
            log.warn("[异步任务] 状态持久化失败: {}", e.getMessage());
        }
    }
}
