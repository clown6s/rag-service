package com.zzp.rag.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.oa.OaClient;
import com.zzp.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SOP 执行器（L2 状态机引擎）：顺序执行步骤，累积上下文。
 * <p>
 * 步骤类型：
 * - tool：内置工具（searchKnowledgeBase 检索 / oa_employee_list 查员工 / oa_leave_submit 提交请假）
 * - extract：LLM 从用户请求提取结构化参数（JSON），供后续 tool/validate 使用
 * - validate：确定性规则校验提取的参数（leave_rules 请假规则），失败则 end 输出错误
 * - llm：指令 + 用户问题 + 累积资料 → 生成
 * - end：结束；带 instruction 时先 LLM 包装流程输出（如回执单号）
 * <p>
 * 软依赖：任何步骤异常抛出，由编排器捕获回退 L3 常规路径（SOP 失败不阻断服务）。
 */
@Slf4j
@Component
public class SopEngine {

    private final RetrievalPipeline retrievalPipeline;
    private final ChatModel chatModel;
    private final OaClient oa;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SopEngine(RetrievalPipeline retrievalPipeline,
                     @Qualifier("chatModel") ChatModel chatModel,
                     OaClient oa) {
        this.retrievalPipeline = retrievalPipeline;
        this.chatModel = chatModel;
        this.oa = oa;
    }

    public record SopResult(String answer, List<String> sources, String retrievalContext) {}

    public SopResult execute(SopDefinition sop, String query, String scopeExpr) {
        StringBuilder context = new StringBuilder();
        List<String> sources = List.of();
        String lastLlmOutput = null;
        Map<String, Object> params = new LinkedHashMap<>();
        String employeeListJson = null;
        boolean aborted = false;

        for (SopDefinition.SopStep step : sop.steps()) {
            if (aborted && !"end".equals(step.type())) {
                continue;   // 校验失败：跳过后续步骤直达 end 输出错误
            }
            switch (step.type()) {
                case "tool" -> {
                    switch (step.toolName() == null ? "" : step.toolName()) {
                        case "searchKnowledgeBase" -> {
                            RetrievalPipeline.PipelineResult pre =
                                    retrievalPipeline.execute(query, scopeExpr, true);
                            sources = pre.getSources();
                            context.append(retrievalPipeline.buildContext(pre, 3000));
                            log.info("[SOP] {} 检索步骤: {} 条片段", sop.code(), pre.segments().size());
                        }
                        case "oa_employee_list" -> {
                            String resp = oa.call("/api/employee/list", "GET", null);
                            employeeListJson = resp;
                            context.append("[员工列表]\n").append(resp).append('\n');
                            log.info("[SOP] {} 查询员工列表完成", sop.code());
                        }
                        case "oa_leave_submit" -> {
                            String resp = submitLeave(params, employeeListJson);
                            context.append("[OA提交结果]\n").append(resp).append('\n');
                            lastLlmOutput = resp;
                            log.info("[SOP] {} 提交请假完成", sop.code());
                        }
                        default -> throw new IllegalStateException("SOP 不支持的工具: " + step.toolName());
                    }
                }
                case "extract" -> {
                    String prompt = step.instruction()
                            + "\n\n今天是 " + LocalDate.now()
                            + "（周一~周日）。用户请求中的相对日期（明天/后天/下周一）必须换算成具体日期。"
                            + "\n\n[用户请求]\n" + query
                            + "\n\n只输出一个 JSON 对象，不要输出任何其他文字。";
                    String out = chatModel.chat(prompt);
                    params = parseJsonParams(out);
                    context.append("[提取参数]\n").append(out).append('\n');
                    log.info("[SOP] {} 参数提取: {}", sop.code(), params.keySet());
                }
                case "validate" -> {
                    String error = validateLeave(params, employeeListJson);
                    if (error != null) {
                        lastLlmOutput = "校验未通过：" + error;
                        aborted = true;   // 校验失败中止流程，end 直接输出错误
                        log.info("[SOP] {} 校验失败: {}", sop.code(), error);
                    } else {
                        context.append("[校验通过]\n");
                        log.info("[SOP] {} 校验通过", sop.code());
                    }
                }
                case "llm" -> {
                    String prompt = step.instruction()
                            + "\n\n[用户问题]\n" + query
                            + "\n\n[检索资料]\n" + context;
                    lastLlmOutput = chatModel.chat(prompt);
                    context.append("\n\n[步骤输出]\n").append(lastLlmOutput);
                    log.info("[SOP] {} LLM 步骤完成（{} 字）", sop.code(),
                            lastLlmOutput != null ? lastLlmOutput.length() : 0);
                }
                case "end" -> {
                    String out = lastLlmOutput;
                    if (out != null && out.startsWith("校验未通过")) {
                        // 校验错误确定性直出：不走 LLM 包装（实况：模型把错误改写成"已提交成功"）
                        log.info("[SOP] {} 执行完成（校验失败直出）", sop.code());
                        return new SopResult(out, sources, context.toString());
                    }
                    if (step.instruction() != null && !step.instruction().isBlank()) {
                        String prompt = step.instruction()
                                + "\n\n[流程上下文]\n" + context
                                + "\n\n[用户请求]\n" + query;
                        out = chatModel.chat(prompt);
                    }
                    log.info("[SOP] {} 执行完成", sop.code());
                    return new SopResult(
                            out != null ? out : "根据现有资料，暂无法回答该问题",
                            sources, context.toString());
                }
                default -> throw new IllegalStateException("SOP 未知步骤类型: " + step.type());
            }
        }
        return new SopResult(
                lastLlmOutput != null ? lastLlmOutput : "根据现有资料，暂无法回答该问题",
                sources, context.toString());
    }

    // ==================== 请假规则校验与提交 ====================

    /** 校验提取参数：名字在员工列表、时间先后、天数范围、事由必填 */
    private String validateLeave(Map<String, Object> params, String employeeListJson) {
        String userName = str(params.get("userName"));
        if (userName.isEmpty()) {
            return "无法确定申请人姓名，请说明是谁请假（如「帮张三请一天假」）";
        }
        if (employeeListJson == null || !employeeListJson.contains("\"" + userName + "\"")) {
            return "OA 员工列表中不存在「" + userName + "」，请核对姓名";
        }
        String start = str(params.get("startTime"));
        String end = str(params.get("endTime"));
        if (start.isEmpty() || end.isEmpty()) {
            return "请假时间不完整，请说明请假起止时间（如「明天到后天」）";
        }
        try {
            LocalDateTime s = LocalDateTime.parse(start.replace(' ', 'T'));
            LocalDateTime e = LocalDateTime.parse(end.replace(' ', 'T'));
            if (!s.isBefore(e)) {
                return "结束时间必须晚于开始时间";
            }
            double days = params.get("days") instanceof Number n
                    ? n.doubleValue() : java.time.Duration.between(s, e).toMinutes() / (8 * 60.0);
            if (days < 0.5 || days > 30) {
                return "请假天数须在 0.5 到 30 天之间（当前 " + String.format("%.1f", days) + " 天）";
            }
        } catch (Exception e) {
            return "请假时间格式无法解析，请用「yyyy-MM-dd HH:mm:ss」或明确日期表述";
        }
        if (str(params.get("reason")).isEmpty()) {
            return "请说明请假事由";
        }
        return null;
    }

    /** 提取参数 + 员工列表 → OA 提交；返回 OA 原始响应 */
    private String submitLeave(Map<String, Object> params, String employeeListJson) {
        String userName = str(params.get("userName"));
        Long userId = null;
        Long deptId = null;
        try {
            JsonNode items = objectMapper.readTree(employeeListJson).path("data");
            for (JsonNode emp : items) {
                if (userName.equals(emp.path("realName").asText(""))) {
                    userId = emp.path("id").asLong();
                    deptId = emp.path("deptId").asLong(0);
                    break;
                }
            }
        } catch (Exception ignored) {
        }
        if (userId == null) {
            return "{\"code\":500,\"message\":\"员工不存在：" + userName + "\"}";
        }
        String start = str(params.get("startTime")).replace(' ', 'T');
        String end = str(params.get("endTime")).replace(' ', 'T');
        int leaveType = params.get("leaveType") instanceof Number n
                && n.intValue() >= 1 && n.intValue() <= 4 ? n.intValue() : 3;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("userName", userName);
        body.put("deptId", deptId);
        body.put("leaveType", leaveType);
        body.put("startTime", start);
        body.put("endTime", end);
        body.put("reason", str(params.get("reason")));
        try {
            return oa.call("/api/leave/submit", "POST", objectMapper.writeValueAsString(body));
        } catch (Exception e) {
            return "{\"code\":500,\"message\":\"提交失败：" + e.getMessage() + "\"}";
        }
    }

    /** LLM 输出容错解析 JSON（提取第一个 {...}） */
    private Map<String, Object> parseJsonParams(String out) {
        if (out == null) {
            return Map.of();
        }
        int i = out.indexOf('{');
        int j = out.lastIndexOf('}');
        if (i < 0 || j <= i) {
            return Map.of();
        }
        try {
            JsonNode node = objectMapper.readTree(out.substring(i, j + 1));
            Map<String, Object> params = new LinkedHashMap<>();
            node.fields().forEachRemaining(f -> params.put(f.getKey(), f.getValue().asText()));
            // 数值字段还原为数字（days/leaveType）
            if (node.has("days") && node.get("days").isNumber()) {
                params.put("days", node.get("days").asDouble());
            }
            if (node.has("leaveType") && node.get("leaveType").isNumber()) {
                params.put("leaveType", node.get("leaveType").asInt());
            }
            return params;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private static String str(Object v) {
        return v == null ? "" : String.valueOf(v).strip();
    }
}
