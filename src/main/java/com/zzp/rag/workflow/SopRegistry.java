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

    private static final List<SopDefinition> DEFS = List.of(
            new SopDefinition(
                    "leave_apply",
                    "请假申请流程",
                    List.of("请假", "休假申请", "年假怎么请", "事假", "病假申请", "请假流程"),
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

    /** 关键词匹配：用户问题包含任一关键词即命中（首个命中优先） */
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
        return null;
    }

    public List<SopDefinition> definitions() {
        return DEFS;
    }
}
