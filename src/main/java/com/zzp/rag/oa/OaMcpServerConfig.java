package com.zzp.rag.oa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletSseServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.ServletContextInitializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Map;

/**
 * OA 审批系统 MCP 服务（真实业务系统接入模板）：进程内注册 SSE 传输，
 * 工具 handler 调 {@link OaClient} 走 OA HTTP API。
 * <p>
 * 技能注册方式（后台技能管理页或 API）：
 *   code=oa_mcp，toolType=mcp，
 *   mcpConfig={"baseUrl":"http://127.0.0.1:8080","sseEndpoint":"/mcp/oa/sse"}
 * 软依赖：OA 未部署时工具返回错误文本，不阻断 Agent 循环。
 */
@Slf4j
@Configuration
@ConditionalOnProperty(name = "rag.oa.enabled", havingValue = "true", matchIfMissing = true)
public class OaMcpServerConfig {

    private static final String SSE_ENDPOINT = "/mcp/oa/sse";
    private static final String MESSAGE_ENDPOINT = "/mcp/oa/message";

    @Bean
    public HttpServletSseServerTransportProvider oaMcpTransportProvider(ObjectMapper objectMapper) {
        return new HttpServletSseServerTransportProvider(
                objectMapper, MESSAGE_ENDPOINT, SSE_ENDPOINT);
    }

    @Bean
    public io.modelcontextprotocol.server.McpSyncServer oaMcpServer(
            @org.springframework.beans.factory.annotation.Qualifier("oaMcpTransportProvider")
            HttpServletSseServerTransportProvider provider, OaClient oa) {
        McpServer.SyncSpecification spec = McpServer.sync(provider)
                .serverInfo("rag-oa-mcp", "1.0.0")
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .tool(leaveSubmitTool(), (exchange, args) -> textResult(oa.call(
                        "/api/leave/submit", "POST", json(Map.of(
                                "userId", args.get("userId"),
                                "userName", args.get("userName"),
                                "deptId", args.get("deptId"),
                                "leaveType", args.get("leaveType"),
                                "startTime", args.get("startTime"),
                                "endTime", args.get("endTime"),
                                "reason", args.get("reason"))))))
                .tool(leaveMyTool(), (exchange, args) -> textResult(oa.call(
                        "/api/leave/my?userId=" + args.get("userId"), "GET", null)))
                .tool(leavePendingTool(), (exchange, args) -> textResult(oa.call(
                        "/api/leave/pending?approverId=" + args.get("approverId"), "GET", null)))
                .tool(leaveApproveTool(), (exchange, args) -> textResult(oa.call(
                        "/api/leave/approve", "POST", json(Map.of(
                                "applyId", args.get("applyId"),
                                "approverId", args.get("approverId"),
                                "approverName", args.get("approverName"),
                                "action", args.get("action"),
                                "comment", args.get("comment"))))))
                .tool(employeeListTool(), (exchange, args) -> textResult(oa.call(
                        "/api/employee/list", "GET", null)))
                .tool(deptTreeTool(), (exchange, args) -> textResult(oa.call(
                        "/api/dept/tree", "GET", null)))
                .tool(bizMetricsTool(), (exchange, args) -> {
                    int days = args.get("days") instanceof Number n ? n.intValue() : 7;
                    String body = oa.call("/api/biz/metrics?days=" + Math.max(1, days), "GET", null);
                    return textResult(chartLine(buildMetricsChart(body), body));
                })
                .tool(bizExpensesTool(), (exchange, args) -> {
                    int days = args.get("days") instanceof Number n ? n.intValue() : 7;
                    String employee = args.get("employee") == null ? null : String.valueOf(args.get("employee"));
                    String body = oa.call("/api/biz/expenses?days=" + Math.max(1, days)
                            + (employee == null || employee.isBlank() ? ""
                            : "&employee=" + java.net.URLEncoder.encode(employee, java.nio.charset.StandardCharsets.UTF_8)),
                            "GET", null);
                    return textResult(chartLine(buildExpenseChart(body), body));
                });
        io.modelcontextprotocol.server.McpSyncServer server = spec.build();
        log.info("[OA-MCP] 服务已装配: {} / {}", SSE_ENDPOINT, MESSAGE_ENDPOINT);
        return server;
    }

    @Bean
    public ServletContextInitializer oaMcpServletInitializer(
            @org.springframework.beans.factory.annotation.Qualifier("oaMcpTransportProvider")
            HttpServletSseServerTransportProvider provider) {
        return servletContext -> {
            jakarta.servlet.ServletRegistration.Dynamic registration =
                    servletContext.addServlet("oaMcpServlet", provider);
            registration.addMapping(SSE_ENDPOINT, MESSAGE_ENDPOINT);
            registration.setAsyncSupported(true);
            registration.setLoadOnStartup(1);
            log.info("[OA-MCP] servlet 已注册: {} {}", SSE_ENDPOINT, MESSAGE_ENDPOINT);
        };
    }

    // ==================== 图表数据构造（渠道端渲染用） ====================

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** metrics 响应 → 多系列折线图表数据 + 表格兜底 */
    private static Map<String, Object> buildMetricsChart(String body) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.has("dates")) {
                return null;
            }
            java.util.List<String> dates = new java.util.ArrayList<>();
            data.path("dates").forEach(d -> dates.add(d.asText()));
            String[] seriesKeys = {"revenue", "cost", "profit"};
            String[] seriesNames = {"营收", "成本", "利润"};
            java.util.List<Object> values = new java.util.ArrayList<>();
            for (int s = 0; s < seriesKeys.length; s++) {
                JsonNode arr = data.path(seriesKeys[s]);
                for (int i = 0; i < arr.size() && i < dates.size(); i++) {
                    Map<String, Object> row = new java.util.LinkedHashMap<>();
                    row.put("date", dates.get(i));
                    row.put("series", seriesNames[s]);
                    row.put("value", arr.get(i).asDouble());
                    values.add(row);
                }
            }
            java.util.List<java.util.List<String>> table = new java.util.ArrayList<>();
            java.util.List<String> head = new java.util.ArrayList<>(java.util.List.of("日期"));
            head.addAll(java.util.List.of("营收", "成本", "利润"));
            table.add(head);
            for (int i = 0; i < dates.size(); i++) {
                table.add(java.util.List.of(dates.get(i),
                        fmt(data.path("revenue").get(i)), fmt(data.path("cost").get(i)),
                        fmt(data.path("profit").get(i))));
            }
            JsonNode sum = data.path("summary");
            Map<String, Object> chart = new java.util.LinkedHashMap<>();
            chart.put("kind", "metrics");
            chart.put("chartType", "line");
            chart.put("xField", "date");
            chart.put("yField", "value");
            chart.put("seriesField", "series");
            chart.put("title", "近" + dates.size() + "天经营数据（元）");
            chart.put("values", values);
            chart.put("table", table);
            chart.put("summary", "合计：营收 " + fmt(sum.path("revenue"))
                    + "，成本 " + fmt(sum.path("cost")) + "，利润 " + fmt(sum.path("profit")));
            return chart;
        } catch (Exception e) {
            log.warn("[OA-MCP] 经营数据图表构造失败: {}", e.getMessage());
            return null;
        }
    }

    /** expenses 响应 → 柱状图表数据 + 表格兜底 */
    private static Map<String, Object> buildExpenseChart(String body) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.has("items")) {
                return null;
            }
            java.util.List<Object> values = new java.util.ArrayList<>();
            java.util.List<java.util.List<String>> table = new java.util.ArrayList<>();
            table.add(java.util.List.of("日期", "类型", "金额", "备注"));
            for (JsonNode it : data.path("items")) {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("date", it.path("d").asText());
                row.put("series", it.path("expenseType").asText());
                row.put("value", it.path("amount").asDouble());
                values.add(row);
                table.add(java.util.List.of(it.path("d").asText(),
                        it.path("expenseType").asText(), fmt(it.path("amount")),
                        it.path("remark").asText("")));
            }
            Map<String, Object> chart = new java.util.LinkedHashMap<>();
            chart.put("kind", "expenses");
            chart.put("chartType", "bar");
            chart.put("xField", "date");
            chart.put("yField", "value");
            chart.put("seriesField", "series");
            chart.put("title", data.path("employee").asText("全员") + "近"
                    + data.path("count").asInt() + "笔报销（元）");
            chart.put("values", values);
            chart.put("table", table);
            chart.put("summary", "合计 " + fmt(data.path("total"))
                    + " 元，共 " + data.path("count").asInt() + " 笔");
            return chart;
        } catch (Exception e) {
            log.warn("[OA-MCP] 报销图表构造失败: {}", e.getMessage());
            return null;
        }
    }

    /** 图表数据以文本标记随工具结果透传（MCP 服务线程与 Agent 线程不同，ThreadLocal 传不过去；
     *  由 McpToolExecutor 在 Agent 线程解析剥离后放回 ThreadLocal） */
    private static String chartLine(Map<String, Object> chart, String body) {
        try {
            return chart == null || chart.isEmpty()
                    ? body
                    : "__CHART__" + MAPPER.writeValueAsString(chart) + "\n" + body;
        } catch (Exception e) {
            return body;
        }
    }

    private static String fmt(JsonNode n) {
        if (n == null || n.isNull()) {
            return "-";
        }
        double v = n.asDouble();
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ==================== 工具定义 ====================

    private McpSchema.Tool leaveSubmitTool() {
        return new McpSchema.Tool("oa_leave_submit",
                "向 OA 审批系统提交请假申请。用户明确要求提交请假/申请休假时调用；"
                        + "时间格式 yyyy-MM-ddTHH:mm:ss，leaveType: 1事假/2病假/3年假。",
                new McpSchema.JsonSchema("object",
                        Map.of(
                                "userId", prop("integer", "申请人用户ID"),
                                "userName", prop("string", "申请人姓名"),
                                "deptId", prop("integer", "部门ID"),
                                "leaveType", prop("integer", "请假类型：1事假 2病假 3年假"),
                                "startTime", prop("string", "开始时间 yyyy-MM-ddTHH:mm:ss"),
                                "endTime", prop("string", "结束时间 yyyy-MM-ddTHH:mm:ss"),
                                "reason", prop("string", "请假事由")),
                        List.of("userId", "userName", "deptId", "leaveType", "startTime", "endTime", "reason"),
                        null, null, null));
    }

    private McpSchema.Tool leaveMyTool() {
        return new McpSchema.Tool("oa_leave_my",
                "查询指定用户在 OA 系统提交的请假申请记录。用户问自己的请假记录时调用。",
                new McpSchema.JsonSchema("object",
                        Map.of("userId", prop("integer", "用户ID")),
                        List.of("userId"), null, null, null));
    }

    private McpSchema.Tool leavePendingTool() {
        return new McpSchema.Tool("oa_leave_pending",
                "查询 OA 系统中待某审批人处理的请假审批任务。用户问待审批事项时调用。",
                new McpSchema.JsonSchema("object",
                        Map.of("approverId", prop("integer", "审批人ID")),
                        List.of("approverId"), null, null, null));
    }

    private McpSchema.Tool leaveApproveTool() {
        return new McpSchema.Tool("oa_leave_approve",
                "审批 OA 请假申请（通过或拒绝）。用户明确要求审批/同意/拒绝某条请假时调用；"
                        + "action: 1通过 2拒绝。",
                new McpSchema.JsonSchema("object",
                        Map.of(
                                "applyId", prop("integer", "申请单ID"),
                                "approverId", prop("integer", "审批人ID"),
                                "approverName", prop("string", "审批人姓名"),
                                "action", prop("integer", "1通过 2拒绝"),
                                "comment", prop("string", "审批意见")),
                        List.of("applyId", "approverId", "approverName", "action"),
                        null, null, null));
    }

    private McpSchema.Tool bizMetricsTool() {
        return new McpSchema.Tool("oa_business_metrics",
                "查询公司最近 N 天经营数据（营收/成本/利润，按天）。"
                        + "用户问经营情况/营收/利润/生意/业绩等数据类问题时调用，days 默认 7。",
                new McpSchema.JsonSchema("object",
                        Map.of("days", prop("integer", "最近天数，默认7")),
                        List.of(), null, null, null));
    }

    private McpSchema.Tool bizExpensesTool() {
        return new McpSchema.Tool("oa_employee_expenses",
                "查询员工最近 N 天报销记录与合计金额。"
                        + "用户问某员工报销/花了多少钱/费用明细时调用；employee 为空查全员。",
                new McpSchema.JsonSchema("object",
                        Map.of(
                                "employee", prop("string", "员工姓名，为空查全员"),
                                "days", prop("integer", "最近天数，默认7")),
                        List.of(), null, null, null));
    }

    private McpSchema.Tool employeeListTool() {
        return new McpSchema.Tool("oa_employee_list",
                "查询 OA 系统全部员工列表。用户问公司/部门有哪些员工时调用。",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
    }

    private McpSchema.Tool deptTreeTool() {
        return new McpSchema.Tool("oa_dept_tree",
                "查询 OA 系统部门树形结构。用户问公司组织架构/部门时调用。",
                new McpSchema.JsonSchema("object", Map.of(), List.of(), null, null, null));
    }

    private static Map<String, Object> prop(String type, String description) {
        return Map.of("type", type, "description", description);
    }

    private static McpSchema.CallToolResult textResult(String body) {
        return McpSchema.CallToolResult.builder().addTextContent(body).build();
    }

    /** 参数 Map → JSON（null 值剔除，避免 OA 侧反序列化报错） */
    private static String json(Map<String, Object> args) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, Object> e : args.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append('"').append(e.getKey()).append("\":");
            Object v = e.getValue();
            if (v instanceof Number || v instanceof Boolean) {
                sb.append(v);
            } else {
                sb.append('"').append(v.toString().replace("\"", "\\\"")).append('"');
            }
        }
        return sb.append('}').toString();
    }
}
