package com.zzp.rag.web;

import com.zzp.rag.ingestion.IngestionPipeline;
import com.zzp.rag.ingestion.IngestionPipeline.IngestionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

/**
 * 文档摄入 API
 * POST   /api/rag/documents/upload   — 上传文档
 * GET    /api/rag/documents/{docId}/status — 查询摄入状态
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/documents")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionPipeline pipeline;

    /** Tika 实例用于真实 MIME 类型检测（防伪装扩展名攻击） */
    private static final Tika TIKA = new Tika();

    /** 允许的 MIME 类型白名单 */
    private static final Set<String> ALLOWED_MIME = Set.of(
            "application/pdf",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/msword",
            "text/plain",
            "text/markdown",
            "text/html",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",   // pptx
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",           // xlsx
            "text/csv",
            // 部分文档（旧 doc/md）可能被识别为 octet-stream，结合扩展名放行
            "application/octet-stream"
    );

    public record UploadResult(String documentId, String fileName, long fileSize) {}

    /**
     * 上传文档，走完整摄入流水线
     */
    @PostMapping("/upload")
    public ApiResponse<UploadResult> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "tags", required = false) String tags) {

        validateFile(file);

        String docId = pipeline.ingest(file, title, tags);

        return ApiResponse.ok(new UploadResult(
                docId,
                file.getOriginalFilename(),
                file.getSize()
        ));
    }

    /**
     * 查询摄入状态
     */
    @GetMapping("/{docId}/status")
    public ApiResponse<IngestionStatus> status(@PathVariable String docId) {
        return ApiResponse.ok(pipeline.getStatus(docId));
    }

    /**
     * 文件校验：扩展名 + 真实 MIME 类型双重校验
     * 防 evil.pdf 伪装攻击（Tika 历史有解析 RCE 漏洞 CVE-2022-30126 等）
     */
    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > 20 * 1024 * 1024) {
            throw new IllegalArgumentException("文件大小不能超过 20MB");
        }
        // 校验1：扩展名白名单
        String name = file.getOriginalFilename();
        if (name == null || !name.matches(".*\\.(pdf|docx?|md|txt|html?|pptx|xlsx|csv)$")) {
            throw new IllegalArgumentException("不支持的文件类型");
        }
        // 校验2：真实 MIME 类型（防伪装扩展名）
        try (InputStream in = file.getInputStream()) {
            String detected = TIKA.detect(in, name);
            if (!ALLOWED_MIME.contains(detected)) {
                throw new IllegalArgumentException("文件实际类型不被支持: " + detected);
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("文件类型校验失败");
        }
    }
}
