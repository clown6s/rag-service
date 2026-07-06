package com.jiadaojia.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析器 — Apache Tika 多格式支持
 * 自动识别格式：PDF / Word / Markdown / TXT / HTML
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();

    /**
     * 从上传文件提取纯文本
     */
    public String parse(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            String text = tika.parseToString(in);
            log.info("[解析] 文件={} 格式={} 文本长度={}",
                    file.getOriginalFilename(),
                    tika.detect(file.getOriginalFilename()),
                    text.length());
            return text;
        } catch (IOException | TikaException e) {
            throw new IngestionException("文档解析失败: " + file.getOriginalFilename(), e);
        }
    }

    /** 摄入异常 */
    public static class IngestionException extends RuntimeException {
        public IngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
