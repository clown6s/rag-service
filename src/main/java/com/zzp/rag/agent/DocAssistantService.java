package com.zzp.rag.agent;

import dev.langchain4j.model.chat.ChatModel;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;

/**
 * 文档助手：AI 生成企业文档（通知/报告/方案/周报）+ 上传文档审核/修改/优化。
 * <p>
 * 文本由 LLM（qwen-plus）产出，docx 由 POI 落盘（标题 + ## 分节 + 段落）。
 * 处理类操作返回 (新文本, 修改摘要)；审核模式不改正文，输出审核意见单。
 */
@Slf4j
@Service
public class DocAssistantService {

    public record ProcessResult(String text, String summary) {}

    /** 生成类：按公文结构撰写正文 */
    private static final String GENERATE_PROMPT = """
            你是企业文档撰写助手。请按【%s】的标准结构撰写《%s》。
            语气要求：%s。
            内容要点：%s
            只输出正文本身（不要"好的""以下是"等前缀或任何解释），
            用 markdown 组织：## 分节标题 + 段落，落款单独一段。
            """;

    /** 处理类：mode → 指令映射 */
    private static final java.util.Map<String, String> MODE_INSTRUCTIONS = java.util.Map.of(
            "review", "你是文档审核员。对原文做校对审阅：不改写正文，只逐条列出问题点"
                    + "（错别字、语病、标点、事实存疑、格式问题），每条标注「第X段：问题描述」；"
                    + "没有问题就输出「未发现问题」。",
            "revise", "你是文档编辑。按用户指令修改原文，直接输出修改后的完整正文，不要解释。",
            "polish", "你是文档编辑。润色优化原文：保持原意与信息完整，改善表达流畅度与专业性，"
                    + "直接输出优化后的完整正文。",
            "condense", "你是文档编辑。压缩精简原文：保留核心信息与结论，删去冗余表述，"
                    + "篇幅缩减 30%%-50%%，直接输出压缩后的完整正文。");

    private final ChatModel chatModel;

    public DocAssistantService(@Qualifier("chatModel") ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    /** 生成文档：LLM 出正文 → docx */
    public byte[] generateDocx(String docType, String title, String points, String tone) {
        String prompt = GENERATE_PROMPT.formatted(
                docType == null || docType.isBlank() ? "通用文档" : docType,
                title == null || title.isBlank() ? "未命名文档" : title,
                tone == null || tone.isBlank() ? "正式、简洁、公文风格" : tone,
                points == null || points.isBlank() ? "（无额外要点，按常规结构撰写）" : points);
        String content = chatModel.chat(prompt);
        return buildDocx(title, content == null ? "" : content);
    }

    /** 处理文档：LLM 按模式处理 → (新文本, 摘要) */
    public ProcessResult process(String text, String mode, String instruction) {
        String base = MODE_INSTRUCTIONS.getOrDefault(mode,
                MODE_INSTRUCTIONS.get("polish"));
        String extra = instruction != null && !instruction.isBlank()
                ? "\n用户的额外要求：" + instruction : "";
        String prompt = base + extra
                + "\n\n完成后，先输出处理结果，然后空一行输出【修改摘要】，"
                + "用 3-5 条要点概括你做的改动。\n\n[原文]\n" + text + "\n[/原文]";
        String out = chatModel.chat(prompt);
        if (out == null || out.isBlank()) {
            return new ProcessResult(text, "处理失败，返回原文");
        }
        int idx = out.indexOf("【修改摘要】");
        String body = idx >= 0 ? out.substring(0, idx).strip() : out;
        String summary = idx >= 0 ? out.substring(idx).strip() : "已完成处理";
        return new ProcessResult(body, summary);
    }

    /** 处理结果 → docx（摘要附在文末） */
    public byte[] toDocx(String title, String body, String summary) {
        return buildDocx(title, body + "\n\n---\n\n" + summary);
    }

    /** markdown 文本 → docx（标题居中加粗 + ## 分节 + 普通段落 + 列表缩进） */
    private byte[] buildDocx(String title, String markdown) {
        try (XWPFDocument doc = new XWPFDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            XWPFParagraph tp = doc.createParagraph();
            tp.setAlignment(ParagraphAlignment.CENTER);
            XWPFRun tr = tp.createRun();
            tr.setBold(true);
            tr.setFontSize(16);
            tr.setText(title == null || title.isBlank() ? "文档" : title);

            for (String line : markdown.split("\n", -1)) {
                String s = line.strip();
                if (s.isEmpty()) {
                    continue;
                }
                if (s.startsWith("---")) {
                    continue;
                }
                XWPFParagraph p = doc.createParagraph();
                XWPFRun r = p.createRun();
                if (s.startsWith("##") || s.startsWith("#")) {
                    String h = s.replaceFirst("^#+\\s*", "");
                    r.setBold(true);
                    r.setFontSize(13);
                    r.setText(h);
                } else if (s.matches("^\\d+[\\.、].*")) {
                    r.setText(s);
                } else if (s.matches("^[-*•].*")) {
                    r.setText("· " + s.replaceFirst("^[-*•]\\s*", ""));
                } else {
                    r.setText(s);
                }
            }
            doc.write(out);
            return out.toByteArray();
        } catch (Exception e) {
            log.error("[文档助手] docx 生成失败: {}", e.getMessage());
            return new byte[0];
        }
    }
}
