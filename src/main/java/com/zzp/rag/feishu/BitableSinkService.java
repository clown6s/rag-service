package com.zzp.rag.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 数据问答 → 飞书多维表格（Bitable）落盘。
 * <p>
 * 每个数据类（metrics/expenses）复用同一个多维表格应用和表：首次创建（带字段定义），
 * 之后每次「存入表格」= 清空旧记录 + 批量写入最新数据（表格永远是最新 N 天快照）。
 * app/table 句柄存内存（重启后重建新应用，旧表保留在飞书，可接受）。
 * 前置条件：飞书应用需开通多维表格 API 权限（bitable:app）。
 */
@Slf4j
@Service
public class BitableSinkService {

    private final FeishuClient client;

    /** 共享多维表格应用（所有数据表挂在同一个应用下）；kind → tableId */
    private volatile String sharedAppToken;
    private final ConcurrentHashMap<String, String> tableIds = new ConcurrentHashMap<>();

    public BitableSinkService(FeishuClient client) {
        this.client = client;
    }

    /** 写入多维表格 → 链接；失败返回 null（多为权限未开通） */
    public String save(String kind, Map<String, Object> chartData, String editorOpenId) {
        try {
            String appToken = sharedAppToken;
            if (appToken == null) {
                synchronized (this) {
                    appToken = sharedAppToken;
                    if (appToken == null) {
                        appToken = client.createBitableApp("经营数据看板");
                        if (appToken == null) {
                            return null;
                        }
                        sharedAppToken = appToken;
                        // 应用创建自带空「数据表」：删掉，打开链接直接落在数据表上
                        for (FeishuClient.BitableTable t : client.listBitableTables(appToken)) {
                            if ("数据表".equals(t.name())) {
                                client.deleteBitableTable(appToken, t.tableId());
                            }
                        }
                    }
                }
            }
            String tableId = tableIds.get(kind);
            if (tableId == null) {
                tableId = client.createBitableTable(appToken,
                        "metrics".equals(kind) ? "每日经营数据" : "员工报销明细",
                        fieldsFor(kind));
                if (tableId == null) {
                    return null;
                }
                tableIds.put(kind, tableId);
            }
            List<Map<String, Object>> records = buildRecords(kind, chartData);
            if (records.isEmpty()) {
                return client.bitableUrl(appToken);
            }
            // 替换模式：清空旧数据再写入（表格永远是最新快照）
            List<String> oldIds = client.listBitableRecordIds(appToken, tableId);
            if (!oldIds.isEmpty()) {
                client.batchDeleteBitableRecords(appToken, tableId, oldIds);
            }
            if (!client.batchCreateBitableRecords(appToken, tableId, records)) {
                return null;
            }
            client.makeBitableOrgReadable(appToken);
            if (editorOpenId != null && !editorOpenId.isBlank()) {
                client.addBitableEditor(appToken, editorOpenId);
            }
            log.info("[多维表格] 已写入 kind={} app={} rows={}", kind, appToken, records.size());
            return client.bitableUrl(appToken);
        } catch (Exception e) {
            log.warn("[多维表格] 写入失败 kind={}: {}", kind, e.getMessage());
            return null;
        }
    }

    /** 表字段定义（type 1文本/2数字/5日期） */
    private List<Map<String, Object>> fieldsFor(String kind) {
        return "metrics".equals(kind)
                ? List.of(
                        field("日期", 5, Map.of("date_formatter", "yyyy/MM/dd")),
                        field("营收（元）", 2, Map.of()),
                        field("成本（元）", 2, Map.of()),
                        field("利润（元）", 2, Map.of()))
                : List.of(
                        field("日期", 5, Map.of("date_formatter", "yyyy/MM/dd")),
                        field("员工", 1, Map.of()),
                        field("部门", 1, Map.of()),
                        field("类型", 1, Map.of()),
                        field("金额（元）", 2, Map.of()),
                        field("备注", 1, Map.of()));
    }

    /** 图表数据 → 记录行（日期转毫秒时间戳） */
    private List<Map<String, Object>> buildRecords(String kind, Map<String, Object> chartData) {
        List<Map<String, Object>> records = new ArrayList<>();
        if ("metrics".equals(kind)) {
            Object tableObj = chartData.get("table");
            if (tableObj instanceof List<?> table) {
                for (int r = 1; r < table.size(); r++) {
                    if (table.get(r) instanceof List<?> row && row.size() >= 4) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("日期", dateToMillis(String.valueOf(row.get(0))));
                        f.put("营收（元）", parseNum(row.get(1)));
                        f.put("成本（元）", parseNum(row.get(2)));
                        f.put("利润（元）", parseNum(row.get(3)));
                        records.add(f);
                    }
                }
            }
        } else {
            Object itemsObj = chartData.get("rawItems");
            if (itemsObj instanceof List<?> items) {
                String employee = String.valueOf(chartData.getOrDefault("employee", ""));
                for (Object it : items) {
                    if (it instanceof Map<?, ?> raw) {
                        Map<String, Object> f = new LinkedHashMap<>();
                        f.put("日期", dateToMillis(String.valueOf(raw.get("d"))));
                        Object emp = raw.get("empName");
                        f.put("员工", String.valueOf(emp == null || String.valueOf(emp).isBlank() ? employee : emp));
                        Object dept = raw.get("deptName");
                        f.put("部门", String.valueOf(dept == null ? "" : dept));
                        Object type = raw.get("expenseType");
                        f.put("类型", String.valueOf(type == null ? "" : type));
                        f.put("金额（元）", parseNum(raw.get("amount")));
                        Object remark = raw.get("remark");
                        f.put("备注", String.valueOf(remark == null ? "" : remark));
                        records.add(f);
                    }
                }
            }
        }
        return records;
    }

    private static Map<String, Object> field(String name, int type, Map<String, Object> property) {
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("field_name", name);
        f.put("type", type);
        if (property != null && !property.isEmpty()) {
            f.put("property", property);
        }
        return f;
    }

    /** "2026-08-10" → 本地零点毫秒时间戳；解析失败返回当前时间 */
    private static Long dateToMillis(String d) {
        try {
            return LocalDate.parse(d).atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private static Object parseNum(Object v) {
        try {
            return Double.parseDouble(String.valueOf(v));
        } catch (Exception e) {
            return 0.0;
        }
    }
}
