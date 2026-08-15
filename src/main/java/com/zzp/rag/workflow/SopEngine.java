package com.zzp.rag.workflow;

import com.zzp.rag.retrieval.RetrievalPipeline;
import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * SOP 执行器（L2 状态机引擎）：顺序执行步骤，累积上下文。
 * <p>
 * - tool 步骤：RetrievalPipeline 全链路检索（员工 knowledgeScope 过滤同样生效）
 * - llm 步骤：指令 + 用户问题 + 累积资料 → 生成（qwen-plus 主力模型）
 * - end 步骤：返回最后 LLM 输出
 * <p>
 * 软依赖：任何步骤异常抛出，由编排器捕获回退 L3 常规路径（SOP 失败不阻断服务）。
 */
@Slf4j
@Component
public class SopEngine {

    private final RetrievalPipeline retrievalPipeline;
    private final ChatModel chatModel;

    public SopEngine(RetrievalPipeline retrievalPipeline,
                     @Qualifier("chatModel") ChatModel chatModel) {
        this.retrievalPipeline = retrievalPipeline;
        this.chatModel = chatModel;
    }

    public record SopResult(String answer, List<String> sources, String retrievalContext) {}

    public SopResult execute(SopDefinition sop, String query, String scopeExpr) {
        StringBuilder context = new StringBuilder();
        List<String> sources = List.of();
        String lastLlmOutput = null;

        for (SopDefinition.SopStep step : sop.steps()) {
            switch (step.type()) {
                case "tool" -> {
                    if ("searchKnowledgeBase".equals(step.toolName())) {
                        // SOP 用确定性检索（不走 Agent 工具循环，流程固定）
                        RetrievalPipeline.PipelineResult pre =
                                retrievalPipeline.execute(query, scopeExpr, true);
                        sources = pre.getSources();
                        context.append(retrievalPipeline.buildContext(pre, 3000));
                        log.info("[SOP] {} 检索步骤: {} 条片段", sop.code(), pre.segments().size());
                    } else {
                        throw new IllegalStateException("SOP 不支持的工具: " + step.toolName());
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
                    log.info("[SOP] {} 执行完成", sop.code());
                    return new SopResult(
                            lastLlmOutput != null ? lastLlmOutput : "根据现有资料，暂无法回答该问题",
                            sources,
                            context.toString());
                }
                default -> throw new IllegalStateException("SOP 未知步骤类型: " + step.type());
            }
        }
        return new SopResult(
                lastLlmOutput != null ? lastLlmOutput : "根据现有资料，暂无法回答该问题",
                sources, context.toString());
    }
}
