package com.zzp.rag.oa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OA 经营/报销数据 → 图表数据构造（渠道端渲染用）。
 * <p>
 * MCP 工具（OaMcpServerConfig）与确定性数据守卫（AgentOrchestrator#tryOaDataLookup）
 * 共用同一份构造逻辑，保证飞书卡片与 Web 图表 schema 一致。
 */
public final class OaChartData {

    private static final Logger log = LoggerFactory.getLogger(OaChartData.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private OaChartData() {}

    /** metrics 响应 → 多系列折线图表数据 + 表格兜底；非 metrics 数据返回 null */
    public static Map<String, Object> metrics(String body) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.has("dates")) {
                return null;
            }
            List<String> dates = new ArrayList<>();
            data.path("dates").forEach(d -> dates.add(d.asText()));
            String[] seriesKeys = {"revenue", "cost", "profit"};
            String[] seriesNames = {"营收", "成本", "利润"};
            List<Object> values = new ArrayList<>();
            for (int s = 0; s < seriesKeys.length; s++) {
                JsonNode arr = data.path(seriesKeys[s]);
                for (int i = 0; i < arr.size() && i < dates.size(); i++) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("date", dates.get(i));
                    row.put("series", seriesNames[s]);
                    row.put("value", arr.get(i).asDouble());
                    values.add(row);
                }
            }
            List<List<String>> table = new ArrayList<>();
            List<String> head = new ArrayList<>(List.of("日期"));
            head.addAll(List.of("营收", "成本", "利润"));
            table.add(head);
            for (int i = 0; i < dates.size(); i++) {
                table.add(List.of(dates.get(i),
                        fmt(data.path("revenue").get(i)), fmt(data.path("cost").get(i)),
                        fmt(data.path("profit").get(i))));
            }
            JsonNode sum = data.path("summary");
            Map<String, Object> chart = new LinkedHashMap<>();
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

    /** expenses 响应 → 柱状图表数据 + 表格兜底；非 expenses 数据返回 null */
    public static Map<String, Object> expenses(String body) {
        try {
            JsonNode data = MAPPER.readTree(body).path("data");
            if (!data.has("items")) {
                return null;
            }
            List<Object> values = new ArrayList<>();
            List<List<String>> table = new ArrayList<>();
            table.add(List.of("日期", "类型", "金额", "备注"));
            List<Map<String, Object>> rawItems = new ArrayList<>();
            for (JsonNode it : data.path("items")) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", it.path("d").asText());
                row.put("series", it.path("expenseType").asText());
                row.put("value", it.path("amount").asDouble());
                values.add(row);
                table.add(List.of(it.path("d").asText(),
                        it.path("expenseType").asText(), fmt(it.path("amount")),
                        it.path("remark").asText("")));
                Map<String, Object> raw = new LinkedHashMap<>();
                raw.put("d", it.path("d").asText());
                raw.put("empName", it.path("empName").asText(""));
                raw.put("deptName", it.path("deptName").asText(""));
                raw.put("expenseType", it.path("expenseType").asText(""));
                raw.put("amount", it.path("amount").asDouble());
                raw.put("remark", it.path("remark").asText(""));
                rawItems.add(raw);
            }
            Map<String, Object> chart = new LinkedHashMap<>();
            chart.put("kind", "expenses");
            chart.put("chartType", "bar");
            chart.put("xField", "date");
            chart.put("yField", "value");
            chart.put("seriesField", "series");
            chart.put("title", data.path("employee").asText("全员") + "近"
                    + data.path("count").asInt() + "笔报销（元）");
            chart.put("employee", data.path("employee").asText("全员"));
            chart.put("values", values);
            chart.put("table", table);
            chart.put("rawItems", rawItems);
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
    public static String chartLine(Map<String, Object> chart, String body) {
        try {
            return chart == null || chart.isEmpty()
                    ? body
                    : "__CHART__" + MAPPER.writeValueAsString(chart) + "\n" + body;
        } catch (Exception e) {
            return body;
        }
    }

    /** 金额格式化：整数不带小数（"5890" 而非 "5890.00"） */
    public static String fmt(JsonNode n) {
        if (n == null || n.isNull()) {
            return "-";
        }
        double v = n.asDouble();
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }
}
