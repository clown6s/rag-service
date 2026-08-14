package com.zzp.rag.mcp.demo;

import java.util.List;
import java.util.Map;

/**
 * 演示 MCP 服务的 mock 数据（纯内存，不依赖任何外部系统）。
 * 内容为示例性质：公司制度条文 + 员工目录，用于验证 MCP 工具链路；
 * 真实业务系统出 MCP server 时替换 handler 为真实业务调用即可（见 DemoMcpServerConfig 注释）。
 */
public final class DemoMcpData {

    private DemoMcpData() {}

    /** 制度库：关键词 → 制度条文（示例） */
    public static final Map<String, String> POLICIES = Map.of(
            "年假", "《员工年休假管理办法》第3条：入职满1年享有5天年假，每满1年增加1天，上限15天；"
                    + "第5条：年假须在自然年度内安排，未休年假按日工资300%补偿。",
            "病假", "《员工考勤与请假管理办法》第8条：病假须提供二级以上医院证明；"
                    + "3天以内由部门负责人审批，3天以上须人力资源部备案；病假期间工资按80%发放。",
            "考勤", "《员工考勤与请假管理办法》第2条：工作时间为9:00-18:00，弹性打卡±30分钟；"
                    + "第3条：迟到早退每月累计3次以内不扣款，超出按每次50元扣款。",
            "报销", "《费用报销管理办法》第4条：单笔500元以内由部门负责人审批，500元以上须分管领导审批；"
                    + "第6条：差旅住宿标准为一线城市600元/晚，其他城市400元/晚，超出部分自理。",
            "差旅", "《差旅管理办法》第2条：出差须提前2个工作日在OA提交出差申请；"
                    + "第5条：交通优先选择高铁二等座或经济舱，特殊情况须事前审批。",
            "加班", "《加班管理办法》第3条：工作日加班按1.5倍工资计发，休息日2倍，法定节假日3倍；"
                    + "第6条：每月加班时长上限36小时，超出须调休处理。"
    );

    /** 员工目录（示例） */
    public static final List<Map<String, String>> EMPLOYEES = List.of(
            Map.of("name", "张伟", "dept", "研发部", "title", "高级工程师", "employeeNo", "E1001", "hireDate", "2021-03-15"),
            Map.of("name", "李娜", "dept", "人力资源部", "title", "HRBP", "employeeNo", "E1002", "hireDate", "2020-07-01"),
            Map.of("name", "王强", "dept", "市场部", "title", "市场经理", "employeeNo", "E1003", "hireDate", "2019-11-20"),
            Map.of("name", "赵敏", "dept", "财务部", "title", "会计主管", "employeeNo", "E1004", "hireDate", "2022-01-10"),
            Map.of("name", "陈杰", "dept", "研发部", "title", "架构师", "employeeNo", "E1005", "hireDate", "2018-05-06"),
            Map.of("name", "刘洋", "dept", "运营部", "title", "运营专员", "employeeNo", "E1006", "hireDate", "2023-02-13"),
            Map.of("name", "孙悦", "dept", "研发部", "title", "前端工程师", "employeeNo", "E1007", "hireDate", "2022-06-27"),
            Map.of("name", "周涛", "dept", "销售部", "title", "大客户销售", "employeeNo", "E1008", "hireDate", "2021-09-08")
    );

    /** 制度检索：关键词包含匹配，未命中给提示 */
    public static String searchPolicy(String keyword) {
        if (keyword == null) {
            return "请提供制度关键词（如：年假、考勤、报销、差旅、加班、病假）";
        }
        for (Map.Entry<String, String> entry : POLICIES.entrySet()) {
            if (keyword.contains(entry.getKey()) || entry.getKey().contains(keyword)) {
                return entry.getValue();
            }
        }
        return "未找到与「" + keyword + "」相关的制度条文。现有制度类别：年假、病假、考勤、报销、差旅、加班。";
    }

    /** 员工检索：按姓名精确匹配 */
    public static String lookupEmployee(String name) {
        if (name == null) {
            return "请提供员工姓名";
        }
        for (Map<String, String> emp : EMPLOYEES) {
            if (emp.get("name").equals(name.strip())) {
                return emp.get("name") + "，工号 " + emp.get("employeeNo") + "，"
                        + emp.get("dept") + emp.get("title") + "，入职日期 " + emp.get("hireDate");
            }
        }
        return "未找到名为「" + name + "」的员工";
    }

    /** 部门员工名单：无部门返回全部 */
    public static String directory(String department) {
        StringBuilder sb = new StringBuilder();
        if (department == null || department.isBlank()) {
            sb.append("全体员工名单：\n");
            for (Map<String, String> emp : EMPLOYEES) {
                sb.append("- ").append(emp.get("name")).append("（").append(emp.get("dept"))
                        .append(" ").append(emp.get("title")).append("）\n");
            }
            return sb.toString();
        }
        int count = 0;
        for (Map<String, String> emp : EMPLOYEES) {
            if (emp.get("dept").contains(department.strip())) {
                sb.append("- ").append(emp.get("name")).append("（").append(emp.get("title"))
                        .append("，工号 ").append(emp.get("employeeNo")).append("）\n");
                count++;
            }
        }
        return count > 0 ? sb.toString() : "未找到「" + department + "」部门的员工";
    }
}
