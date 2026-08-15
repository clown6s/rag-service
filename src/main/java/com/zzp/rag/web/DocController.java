package com.zzp.rag.web;

import com.zzp.rag.agent.DocAssistantService;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.audit.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 文档助手端点：AI 生成文档 + 上传文档审核/修改/优化。
 * 返回 docx 附件流；处理类操作把修改摘要放进响应头 X-Doc-Summary（URL 编码）。
 * 审计端点 agent/docgen、agent/docprocess（合规：AI 产出的文档操作全程留痕）。
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/agent/docs")
public class DocController {

    public record GenerateRequest(String docType, String title, String points, String tone) {}

    private final DocAssistantService docAssistant;
    private final com.zzp.rag.agent.DocWizardService docWizardService;
    private final com.zzp.rag.feishu.FeishuClient feishuClient;
    private final AuditService auditService;

    public DocController(DocAssistantService docAssistant,
                         com.zzp.rag.agent.DocWizardService docWizardService,
                         com.zzp.rag.feishu.FeishuClient feishuClient,
                         AuditService auditService) {
        this.docAssistant = docAssistant;
        this.docWizardService = docWizardService;
        this.feishuClient = feishuClient;
        this.auditService = auditService;
    }

    // ==================== 文档工作台（编辑器内生成/修改/导出） ====================

    public record GenerateTextRequest(String docType, String title, String points,
                                      String tone, String issuer) {}

    public record ReviseTextRequest(String content, String instruction) {}

    public record ExportRequest(String title, String content, String format) {}

    /** 生成正文 markdown（工作台编辑器渲染用） */
    @PostMapping("/generate-text")
    public ApiResponse<java.util.Map<String, String>> generateText(@RequestBody GenerateTextRequest req) {
        try {
            String markdown = docAssistant.generateContent(
                    req.docType(), req.title(), req.points(), req.tone(), req.issuer());
            return ApiResponse.ok(java.util.Map.of("markdown", markdown));
        } catch (Exception e) {
            return ApiResponse.fail("生成失败: " + e.getMessage());
        }
    }

    /** AI 修改正文（按指令增量改 + 修改摘要） */
    @PostMapping("/revise-text")
    public ApiResponse<java.util.Map<String, String>> reviseText(@RequestBody ReviseTextRequest req) {
        try {
            if (req.content() == null || req.content().isBlank()) {
                return ApiResponse.fail("内容为空");
            }
            DocAssistantService.ProcessResult result =
                    docAssistant.process(req.content(), "revise", req.instruction());
            return ApiResponse.ok(java.util.Map.of(
                    "markdown", result.text(), "summary", result.summary()));
        } catch (Exception e) {
            return ApiResponse.fail("修改失败: " + e.getMessage());
        }
    }

    /** 导出：cloud=飞书云文档（返回链接文本，失败回退 docx 文件）；docx/pdf/md/txt=文件流 */
    @PostMapping("/export")
    public org.springframework.http.ResponseEntity<byte[]> export(@RequestBody ExportRequest req) {
        try {
            String format = req.format() == null ? "docx" : req.format().toLowerCase();
            String footer = "---\n\n本文引用资料以知识库原文为准，由 AI 辅助生成，请人工核对后发布。";
            if ("cloud".equals(format)) {
                try {
                    String docId = feishuClient.createCloudDoc(req.title());
                    java.util.List<String> blockIds = docId != null
                            ? feishuClient.writeCloudDocBlocks(docId,
                                    docWizardService.toBlocksForExport(req.content() + "\n\n" + footer, req.title()))
                            : null;
                    if (docId != null && blockIds != null && !blockIds.isEmpty()) {
                        feishuClient.makeCloudDocOrgReadable(docId);
                        String url = feishuClient.cloudDocUrl(docId);
                        return org.springframework.http.ResponseEntity.ok()
                                .header("Content-Type", "text/plain; charset=UTF-8")
                                .body(url.getBytes(StandardCharsets.UTF_8));
                    }
                } catch (Exception e) {
                    log.warn("[文档工作台] 云文档导出失败，回退文件: {}", e.getMessage());
                }
                format = "docx";   // 云文档不可用 → 回退文件下载
            }
            byte[] bytes = docAssistant.toFile(format, req.title(), req.content(), footer);
            String fileName = (req.title() == null || req.title().isBlank() ? "文档" : req.title())
                    .replaceAll("[\\\\/:*?\"<>|\\s]+", "_") + "." + format;
            return org.springframework.http.ResponseEntity.ok()
                    .header("Content-Type", "application/octet-stream")
                    .header("Content-Disposition", "attachment; filename*=UTF-8''"
                            + java.net.URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                    .body(bytes);
        } catch (Exception e) {
            return org.springframework.http.ResponseEntity.status(500)
                    .body(("导出失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 对话生成的历史文件列表（按登录用户隔离；admin 看自己的——管理全量走数据目录） */
    @org.springframework.web.bind.annotation.GetMapping("/history")
    public ApiResponse<java.util.List<java.util.Map<String, Object>>> history() {
        String user = com.zzp.rag.audit.AuditEvent.AuditContext.capture().user();
        return ApiResponse.ok(docWizardService.history(user == null ? "anon" : user));
    }

    /** 下载历史文件（文件名白名单防路径穿越） */
    @org.springframework.web.bind.annotation.GetMapping("/history/{name}")
    public org.springframework.http.ResponseEntity<byte[]> historyFile(
            @org.springframework.web.bind.annotation.PathVariable String name) {
        String user = com.zzp.rag.audit.AuditEvent.AuditContext.capture().user();
        byte[] bytes = docWizardService.historyFile(user == null ? "anon" : user, name);
        if (bytes == null) {
            return org.springframework.http.ResponseEntity.notFound().build();
        }
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename*=UTF-8''"
                        + java.net.URLEncoder.encode(name, StandardCharsets.UTF_8))
                .body(bytes);
    }

    /** 生成文档 → docx 附件 */
    @PostMapping("/generate")
    public org.springframework.http.ResponseEntity<byte[]> generate(@RequestBody GenerateRequest req) {
        AuditEvent.AuditContext ctx = AuditEvent.AuditContext.capture();
        long start = System.currentTimeMillis();
        try {
            byte[] docx = docAssistant.generateDocx(req.docType(), req.title(), req.points(), req.tone());
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "agent/docgen",
                    req.docType(), req.title() + " | " + (req.points() == null ? "" : req.points()), 0,
                    List.of(), 0, false, 0, null, docx.length,
                    System.currentTimeMillis() - start, "SUCCESS", null, 0));
            return attachment(docx, safeFileName(req.title()) + ".docx");
        } catch (Exception e) {
            log.error("[文档助手] 生成失败", e);
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "agent/docgen",
                    req.docType(), req.title(), 0, List.of(), 0, false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
            return org.springframework.http.ResponseEntity.status(500)
                    .body(("生成失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** 处理文档（审核/修改/优化）→ docx 附件 + X-Doc-Summary 摘要头 */
    @PostMapping("/process")
    public org.springframework.http.ResponseEntity<byte[]> process(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "polish") String mode,
            @RequestParam(required = false) String instruction) {
        AuditEvent.AuditContext ctx = AuditEvent.AuditContext.capture();
        long start = System.currentTimeMillis();
        try {
            if (file == null || file.isEmpty()) {
                return org.springframework.http.ResponseEntity.badRequest()
                        .body("请上传文件".getBytes(StandardCharsets.UTF_8));
            }
            String text = new Tika().parseToString(new java.io.ByteArrayInputStream(file.getBytes()));
            if (text.isBlank() || text.length() > 100_000) {
                return org.springframework.http.ResponseEntity.badRequest()
                        .body("文件内容为空或超过 10 万字符限制".getBytes(StandardCharsets.UTF_8));
            }
            DocAssistantService.ProcessResult result = docAssistant.process(text, mode, instruction);
            String baseName = file.getOriginalFilename() == null ? "文档"
                    : file.getOriginalFilename().replaceFirst("\\.[^.]+$", "");
            byte[] docx = docAssistant.toDocx(baseName + "（已处理）", result.text(), result.summary());
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "agent/docprocess",
                    mode, file.getOriginalFilename(), 0, List.of(), 0, false, 0,
                    AuditEvent.truncate(result.summary(), 500), docx.length,
                    System.currentTimeMillis() - start, "SUCCESS", null, 0));
            org.springframework.http.ResponseEntity.BodyBuilder builder =
                    org.springframework.http.ResponseEntity.ok();
            builder.header("X-Doc-Summary",
                    URLEncoder.encode(result.summary(), StandardCharsets.UTF_8));
            builder.header("Content-Type",
                    "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
            builder.header("Content-Disposition",
                    "attachment; filename*=UTF-8''" + URLEncoder.encode(safeFileName(baseName), StandardCharsets.UTF_8) + ".docx");
            return builder.body(docx);
        } catch (Exception e) {
            log.error("[文档助手] 处理失败", e);
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "agent/docprocess",
                    mode, file != null ? file.getOriginalFilename() : null, 0, List.of(), 0,
                    false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
            return org.springframework.http.ResponseEntity.status(500)
                    .body(("处理失败: " + e.getMessage()).getBytes(StandardCharsets.UTF_8));
        }
    }

    private org.springframework.http.ResponseEntity<byte[]> attachment(byte[] docx, String fileName) {
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Type",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .header("Content-Disposition",
                        "attachment; filename*=UTF-8''" + URLEncoder.encode(fileName, StandardCharsets.UTF_8))
                .body(docx);
    }

    /** 文件名安全化（去路径分隔符与非法字符） */
    private String safeFileName(String name) {
        String n = name == null || name.isBlank() ? "文档" : name.strip();
        return n.replaceAll("[\\\\/:*?\"<>|\\s]+", "_").replaceAll("_+$", "");
    }
}
