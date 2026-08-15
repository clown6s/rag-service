package com.zzp.rag.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.retrieval.RetrievalPipeline;
import com.zzp.rag.web.eval.EvalSetStore;
import dev.langchain4j.data.segment.TextSegment;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 评测界面后端：评测集管理（本地 JSON 存储）+ 检索质量评测。
 * <p>
 * 指标分两层：
 * - 本地确定性指标（零 LLM/零 sidecar 依赖，永远可用）：命中率（参考答案的关键片段
 *   出现在检索上下文里）、MRR（首个命中片段的倒数排名）
 * - Ragas 指标（软依赖）：检索结果同步提交 ai-sidecar /evaluate 异步任务，
 *   返回 task_id 供轮询（LLM 评判，分钟级耗时）
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/admin/eval")
public class EvalController {

    public record CreateSetRequest(String name, String itemsText) {}

    public record ItemResult(String question, boolean hit, double mrr, String topSource) {}

    public record RunResult(String setId, int total, int evaluated, double hitRate,
                            double mrr, List<ItemResult> items, String ragasTaskId) {}

    private final EvalSetStore store;
    private final RetrievalPipeline retrievalPipeline;
    private final com.zzp.rag.agent.AgentOrchestrator orchestrator;
    private final AsyncTaskService asyncTaskService;
    private final ObjectMapper objectMapper;

    @Value("${rag.sidecar-url:http://127.0.0.1:8081}")
    private String sidecarUrl;

    @Value("${rag.sidecar-token:}")
    private String sidecarToken;

    public EvalController(EvalSetStore store, RetrievalPipeline retrievalPipeline,
                          com.zzp.rag.agent.AgentOrchestrator orchestrator,
                          AsyncTaskService asyncTaskService, ObjectMapper objectMapper) {
        this.store = store;
        this.retrievalPipeline = retrievalPipeline;
        this.orchestrator = orchestrator;
        this.asyncTaskService = asyncTaskService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/sets")
    public ApiResponse<List<EvalSetStore.EvalSetView>> sets() {
        return ApiResponse.ok(store.list());
    }

    @PostMapping("/sets")
    public ApiResponse<EvalSetStore.EvalSetView> create(@RequestBody CreateSetRequest req) {
        try {
            List<EvalSetStore.EvalItem> items = EvalSetStore.parseItems(req.itemsText());
            if (items.isEmpty()) {
                return ApiResponse.fail("至少需要一条评测项（每行一条，格式：问题|参考答案）");
            }
            EvalSetStore.EvalSet set = store.create(req.name(), items);
            return ApiResponse.ok(new EvalSetStore.EvalSetView(
                    set.id(), set.name(), set.items().size(), set.createdAt()));
        } catch (Exception e) {
            log.error("[评测集] 创建失败", e);
            return ApiResponse.fail("创建失败: " + e.getMessage());
        }
    }

    @DeleteMapping("/sets/{id}")
    public ApiResponse<Boolean> delete(@PathVariable String id) {
        return store.delete(id) ? ApiResponse.ok(true) : ApiResponse.fail("评测集不存在或删除失败");
    }

    /** 运行检索评测（异步任务：提交即返回 taskId，轮询 /runs/{taskId} 取结果） */
    @PostMapping("/sets/{id}/run")
    public ApiResponse<java.util.Map<String, String>> run(@PathVariable String id) {
        EvalSetStore.EvalSet set = store.get(id);
        if (set == null) {
            return ApiResponse.fail("评测集不存在");
        }
        String[] taskIdHolder = new String[1];   // lambda 捕获：submit 返回前 taskId 未初始化
        String taskId = asyncTaskService.submit("评测: " + set.name(), updater -> {
            List<ItemResult> results = new ArrayList<>();
            List<Map<String, Object>> ragasRows = new ArrayList<>();
            int evaluated = 0;
            int hits = 0;
            double mrrSum = 0;
            int total = set.items().size();
            int done = 0;
            for (EvalSetStore.EvalItem item : set.items()) {
                if (item.groundTruth() == null || item.groundTruth().isBlank()) {
                    done++;
                    continue;   // 无参考答案的条目跳过指标（保留在集里但不可评）
                }
                updater.progress("检索与评估中 " + done + "/" + total);
                evaluated++;
                RetrievalPipeline.PipelineResult pre =
                        retrievalPipeline.execute(item.question(), null, false);
                List<TextSegment> segments = pre.segments().stream().limit(5).toList();
                List<String> contexts = segments.stream().map(TextSegment::text).toList();
                String topSource = "-";
                if (!segments.isEmpty()) {
                    Map<String, Object> meta = segments.get(0).metadata().toMap();
                    topSource = String.valueOf(meta.getOrDefault("source",
                            meta.getOrDefault("title", "-")));
                }
                double mrr = computeMrr(contexts, item.groundTruth());
                boolean hit = mrr > 0;
                hits += hit ? 1 : 0;
                mrrSum += mrr;
                results.add(new ItemResult(item.question(), hit, mrr, topSource));
                // Ragas 行：response = 真实问答链路生成（faithfulness 需要；逐条独立会话防记忆串扰）
                String response = null;
                try {
                    response = orchestrator.answer(
                            "eval-" + set.id() + "-" + evaluated, item.question(), null).answer();
                } catch (Exception e) {
                    log.warn("[评测] 答案生成失败（跳过 Ragas faithfulness）: {}", e.getMessage());
                }
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("question", item.question());
                row.put("contexts", contexts);
                row.put("ground_truth", item.groundTruth());
                if (response != null) {
                    row.put("response", response);
                }
                ragasRows.add(row);
                done++;
            }
            updater.progress("提交 Ragas 评判…");
            String ragasTaskId = submitRagas(ragasRows);
            double hitRate = evaluated == 0 ? 0 : hits * 100.0 / evaluated;
            double mrr = evaluated == 0 ? 0 : mrrSum / evaluated;
            RunResult result = new RunResult(set.id(), set.items().size(), evaluated,
                    Math.round(hitRate * 10) / 10.0, Math.round(mrr * 1000) / 1000.0,
                    results, ragasTaskId);
            asyncTaskService.setResult(taskIdHolder[0], result);
        });
        taskIdHolder[0] = taskId;
        return ApiResponse.ok(java.util.Map.of("taskId", taskId));
    }

    /** 评测任务状态查询 */
    @GetMapping("/runs/{taskId}")
    public ApiResponse<AsyncTaskService.TaskView> runStatus(@PathVariable String taskId) {
        AsyncTaskService.TaskView task = asyncTaskService.get(taskId);
        return task != null ? ApiResponse.ok(task) : ApiResponse.fail("任务不存在");
    }

    /**
     * 确定性命中判定：参考答案按标点切段，任一 ≥4 字片段出现在任一检索上下文中即命中。
     * 返回 1/rank（首个命中片段排名），未命中返回 0。
     */
    private double computeMrr(List<String> contexts, String groundTruth) {
        List<String> probes = new ArrayList<>();
        for (String seg : groundTruth.split("[，。；、：,.;:！？!?\\s]+")) {
            String s = normalize(seg);
            if (s.length() >= 4) {
                probes.add(s);
            }
        }
        if (probes.isEmpty()) {
            return 0;
        }
        for (int i = 0; i < contexts.size(); i++) {
            String ctx = normalize(contexts.get(i));
            for (String p : probes) {
                if (ctx.contains(p)) {
                    return 1.0 / (i + 1);
                }
            }
        }
        return 0;
    }

    private static String normalize(String s) {
        return s == null ? "" : s.replaceAll("[\\p{P}\\p{S}\\s]", "").toLowerCase();
    }

    /** 提交 Ragas 异步评测（软依赖：sidecar 不可达返回 null，不阻断本地指标） */
    private String submitRagas(List<Map<String, Object>> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        try {
            String body = objectMapper.writeValueAsString(Map.of(
                    "testset", rows,
                    "thresholds", Map.of("context_precision", 0.5)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(sidecarUrl + "/evaluate"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + (sidecarToken == null ? "" : sidecarToken))
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                log.warn("[评测] Ragas 任务提交失败 http={}", resp.statusCode());
                return null;
            }
            JsonNode node = objectMapper.readTree(resp.body());
            String taskId = node.path("task_id").asText(null);
            log.info("[评测] Ragas 任务已提交 task_id={} 条目={}", taskId, rows.size());
            return taskId;
        } catch (Exception e) {
            log.warn("[评测] Ragas 提交异常（软依赖，跳过）: {}", e.getMessage());
            return null;
        }
    }
}
