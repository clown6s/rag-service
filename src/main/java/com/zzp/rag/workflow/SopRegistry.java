package com.zzp.rag.workflow;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SOP 注册表（L2 状态机）：内置 SOP 定义 + 关键词匹配。
 * <p>
 * 演进路径：当前代码注册（内置 SOP）；后续管理后台可视化配置（画步骤+条件），
 * 定义落 DB（sop 表），本类退化为加载器——演进路线与架构文档 3.4 一致。
 */
@Slf4j
@Component
public class SopRegistry {

    private final com.zzp.rag.mysql.service.SopStoreService sopStore;

    /** DB SOP 缓存（30s TTL；管理后台变更后 reload() 立即生效） */
    private volatile List<SopDefinition> dbDefs = List.of();
    private volatile long dbLoadedAt = 0;

    public SopRegistry(com.zzp.rag.mysql.service.SopStoreService sopStore) {
        this.sopStore = sopStore;
    }

    /** 管理后台变更后调用：立即重载 DB SOP */
    public void reload() {
        dbDefs = sopStore.loadEnabled();
        dbLoadedAt = System.currentTimeMillis();
        log.info("[SOP] 已重载 DB 定义 {} 条", dbDefs.size());
    }

    private List<SopDefinition> dbDefinitions() {
        if (System.currentTimeMillis() - dbLoadedAt > 30_000) {
            reload();
        }
        return dbDefs;
    }

    private static final List<SopDefinition> DEFS = List.of(
            new SopDefinition(
                    "leave_action",
                    "请假申请办理（OA 四步：查员工→校验规则→提交→回执）",
                    List.of("提交请假", "帮我请假", "帮我请", "申请年假", "请事假", "请病假",
                            "请年假", "请调休", "请个假", "请一天假", "请两天假", "请三天假",
                            "请半天假", "请假一天", "请假两天", "请假三天"),
                    List.of(
                            new SopDefinition.SopStep("tool", "oa_employee_list", null),
                            new SopDefinition.SopStep("extract", null,
                                    "从用户的请假请求中提取以下字段："
                                            + "userName（申请人姓名）、startTime（请假开始时间，格式 yyyy-MM-dd HH:mm:ss，"
                                            + "整天从 00:00:00 开始）、endTime（请假结束时间，整天到 23:59:59）、"
                                            + "days（请假天数，数字）、leaveType（1事假/2病假/3年假/4调休）、"
                                            + "reason（请假事由）"),
                            new SopDefinition.SopStep("validate", "leave_rules", null),
                            new SopDefinition.SopStep("tool", "oa_leave_submit", null),
                            new SopDefinition.SopStep("end", null,
                                    "请用友好简洁的语气告知用户请假办理结果："
                                            + "引用流程上下文里 OA 返回的申请单号与状态；"
                                            + "若流程中校验未通过，直接转达校验错误，不要编造其他信息。"))),
            new SopDefinition(
                    "leave_apply",
                    "请假申请流程",
                    // 收窄关键词：裸"请假"会截胡"帮我提交请假申请"类 OA 操作请求
                    // （操作走 Agent 工具循环调 oa_leave_submit，流程咨询才走本 SOP）
                    List.of("请假流程", "休假申请", "年假怎么请", "病假申请", "请假规定", "请假制度", "怎么请假"),
                    List.of(
                            new SopDefinition.SopStep("tool", "searchKnowledgeBase", null),
                            new SopDefinition.SopStep("llm", null,
                                    "你是企业流程助手。根据下面的制度资料，为用户整理请假申请的完整流程步骤"
                                            + "（申请方式、提前时间、审批节点、材料要求）。"
                                            + "只依据资料输出，资料中没有的环节不要编造；"
                                            + "资料完全无关时输出\"根据现有资料，暂无法回答该问题\"。"),
                            new SopDefinition.SopStep("end", null, null))),
            new SopDefinition(
                    "onboard_guide",
                    "入职报到指引",
                    List.of("入职", "报到", "新员工", "入职手续", "入职流程"),
                    List.of(
                            new SopDefinition.SopStep("tool", "searchKnowledgeBase", null),
                            new SopDefinition.SopStep("llm", null,
                                    "你是企业流程助手。根据下面的制度资料，为新员工整理入职报到清单"
                                            + "（材料、办理地点、注意事项、首日安排）。"
                                            + "只依据资料输出，资料中没有的环节不要编造；"
                                            + "资料完全无关时输出\"根据现有资料，暂无法回答该问题\"。"),
                            new SopDefinition.SopStep("end", null, null))));

    /** 关键词匹配：内置优先，再查 DB 定义（用户问题包含任一关键词即命中） */
    public SopDefinition match(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        for (SopDefinition def : DEFS) {
            for (String kw : def.keywords()) {
                if (query.contains(kw)) {
                    log.info("[SOP] 命中 {}（关键词: {}）", def.code(), kw);
                    return def;
                }
            }
        }
        for (SopDefinition def : dbDefinitions()) {
            for (String kw : def.keywords()) {
                if (query.contains(kw)) {
                    log.info("[SOP] 命中 DB 定义 {}（关键词: {}）", def.code(), kw);
                    return def;
                }
            }
        }
        return null;
    }

    public List<SopDefinition> definitions() {
        List<SopDefinition> all = new java.util.ArrayList<>(DEFS);
        all.addAll(dbDefinitions());
        return all;
    }
}
