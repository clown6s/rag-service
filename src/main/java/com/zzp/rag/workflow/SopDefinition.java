package com.zzp.rag.workflow;

import java.util.List;

/**
 * SOP 工作流定义（L2 编排层：确定性多步流程，该严格的地方严格）。
 * <p>
 * 声明式结构：关键词触发 → 步骤顺序执行。
 * 步骤类型：
 * - tool：执行内置工具（当前支持 searchKnowledgeBase，经 RetrievalPipeline 直接调用）
 * - llm：LLM 组织步骤（指令 + 累积上下文 + 用户问题）
 * - end：结束，返回最后一步 LLM 输出
 * <p>
 * 与 L3（Agent 工具循环）的分工：规章制度类流程（请假/入职/报销）用 SOP 卡死步骤，
 * 自由问答用 L3——流程固定 = 无自由度 = 不跑偏（防编造的另一个维度）。
 */
public record SopDefinition(
        String code,
        String name,
        List<String> keywords,   // 触发关键词（用户问题包含任一即命中）
        List<SopStep> steps) {

    public record SopStep(String type,   // tool | llm | end
                          String toolName,   // tool 步骤的工具名
                          String instruction) {}  // llm 步骤的指令 / tool 步骤的检索补充说明
}
