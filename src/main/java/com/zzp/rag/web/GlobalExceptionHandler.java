package com.zzp.rag.web;

import com.zzp.rag.ingestion.DocumentParser.IngestionException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理器
 * 统一返回 ApiResponse 结构化 JSON
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(IngestionException.class)
    public ResponseEntity<ApiResponse<Void>> handleIngestion(IngestionException e) {
        log.error("[异常] 文档摄入失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(e.getMessage()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> handleIllegalArg(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(e.getMessage()));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiResponse<Void>> handleMaxUpload(MaxUploadSizeExceededException e) {
        return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(ApiResponse.fail("文件大小超过限制"));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .orElse("参数校验失败");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(ApiResponse.fail(msg));
    }

    /**
     * 静态资源 404（浏览器访问根路径、favicon、探测请求等）——纯噪声，不按 ERROR 记录
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(
            org.springframework.web.servlet.resource.NoResourceFoundException e) {
        log.debug("[异常] 无匹配资源: {}", e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(ApiResponse.fail("资源不存在"));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleGeneric(Exception e) {
        String traceId = java.util.UUID.randomUUID().toString().substring(0, 8);
        log.error("[异常] 未预期错误 traceId={}", traceId, e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail("服务器内部错误 (traceId=" + traceId + ")"));
    }
}
