package com.zzp.rag.ingestion;

import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 内存字节的 MultipipartFile 适配（飞书转发文件等非上传场景复用 DocumentParser/IngestionPipeline）。
 */
public class ByteMultipartFile implements MultipartFile {

    private final String fileName;
    private final byte[] bytes;

    public ByteMultipartFile(String fileName, byte[] bytes) {
        this.fileName = fileName;
        this.bytes = bytes;
    }

    @Override
    public String getName() {
        return fileName;
    }

    @Override
    public String getOriginalFilename() {
        return fileName;
    }

    @Override
    public String getContentType() {
        return "application/octet-stream";
    }

    @Override
    public boolean isEmpty() {
        return bytes == null || bytes.length == 0;
    }

    @Override
    public long getSize() {
        return bytes == null ? 0 : bytes.length;
    }

    @Override
    public byte[] getBytes() {
        return bytes;
    }

    @Override
    public InputStream getInputStream() {
        return new ByteArrayInputStream(bytes);
    }

    @Override
    public void transferTo(Path dest) throws IOException {
        Files.write(dest, bytes);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void transferTo(File dest) throws IOException {
        Files.write(dest.toPath(), bytes);
    }
}
