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
    private final AuditService auditService;

    public DocController(DocAssistantService docAssistant, AuditService auditService) {
        this.docAssistant = docAssistant;
        this.auditService = auditService;
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
