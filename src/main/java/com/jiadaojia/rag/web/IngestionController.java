package com.jiadaojia.rag.web;

import com.jiadaojia.rag.ingestion.IngestionPipeline;
import com.jiadaojia.rag.ingestion.IngestionPipeline.IngestionStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 文档摄入 API
 *
 * POST   /api/rag/documents/upload   — 上传文档
 * GET    /api/rag/documents/{docId}/status — 查询摄入状态
 */
@Slf4j
@RestController
@RequestMapping("/api/rag/documents")
@RequiredArgsConstructor
public class IngestionController {

    private final IngestionPipeline pipeline;

    /**
     * 上传文档，走完整摄入流水线
     */
    @PostMapping("/upload")
    public ResponseEntity<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "tags", required = false) String tags) {

        validateFile(file);

        String docId = pipeline.ingest(file, title, tags);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "document_id", docId,
                "file_name", file.getOriginalFilename(),
                "file_size", file.getSize()
        ));
    }

    /**
     * 查询摄入状态
     */
    @GetMapping("/{docId}/status")
    public ResponseEntity<IngestionStatus> status(@PathVariable String docId) {
        return ResponseEntity.ok(pipeline.getStatus(docId));
    }

    private void validateFile(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("文件不能为空");
        }
        if (file.getSize() > 20 * 1024 * 1024) { // 20MB
            throw new IllegalArgumentException("文件大小不能超过 20MB");
        }
        String name = file.getOriginalFilename();
        if (name == null || !name.matches(".*\\.(pdf|docx?|md|txt|html?)$")) {
            throw new IllegalArgumentException("不支持的文件类型");
        }
    }
}
