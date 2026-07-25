package com.zzp.rag.ingestion;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.extractor.WordExtractor;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * 文档解析器
 * <p>
 * .doc  → Apache POI HWPFDocument + WordExtractor（直接底层提取，比 Tika 更完整）
 * .docx → Apache POI XWPFDocument + XWPFWordExtractor
 * 其他   → Apache Tika AutoDetectParser
 * <p>
 * 关键修复：Tika parseToString/AutoDetectParser 对某些 .doc 文件会丢失内容
 * （文本框、表格、分节等），改用 POI 直接提取确保完整。
 */
@Slf4j
@Component
public class DocumentParser {

    private final Tika tika = new Tika();
    private final Parser autoDetectParser = new AutoDetectParser();

    /**
     * 从上传文件提取纯文本（全量提取，不截断）
     */
    public String parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        String text = "";

        try {
            if (fileName != null && fileName.toLowerCase().endsWith(".doc")
                    && !fileName.toLowerCase().endsWith(".docx")) {
                // .doc 文件用 POI WordExtractor 直接提取
                text = parseDocWithPOI(file.getBytes());
            } else if (fileName != null && fileName.toLowerCase().endsWith(".docx")) {
                // .docx 文件用 POI XWPFWordExtractor 直接提取
                text = parseDocxWithPOI(file.getBytes());
            } else {
                // 其他格式用 Tika
                text = parseWithTika(file.getInputStream());
            }

            // 清理多余空白
            text = text.replaceAll("\\n{3,}", "\n\n").trim();

            String preview = text.length() > 500 ? text.substring(0, 500) : text;
            log.info("[解析] 文件={} 文本长度={} 预览前500字=[{}]", fileName, text.length(), preview);

            if (text.isBlank()) {
                log.warn("[解析] 文件={} 主解析为空，尝试 Tika fallback", fileName);
                try (InputStream in = file.getInputStream()) {
                    text = tika.parseToString(in);
                    text = text.replaceAll("\\n{3,}", "\n\n").trim();
                    log.info("[解析] fallback 文本长度={}", text.length());
                }
            }

            return text;
        } catch (Exception e) {
            log.error("[解析] 文件={} 解析失败", fileName, e);
            throw new IngestionException("文档解析失败: " + fileName, e);
        }
    }

    /**
     * 用 POI WordExtractor 解析 .doc 文件
     * 比 Tika 更底层，能提取主文档、脚注、尾注、批注、表格等
     */
    private String parseDocWithPOI(byte[] bytes) throws IOException {
        try (HWPFDocument doc = new HWPFDocument(new ByteArrayInputStream(bytes))) {
            WordExtractor extractor = new WordExtractor(doc);
            StringBuilder sb = new StringBuilder();

            // 1. WordExtractor 主文本（包含主文档所有段落）
            String mainText = extractor.getText();
            sb.append(mainText);
            log.info("[解析-POI] .doc WordExtractor 主文本长度={}", mainText.length());

            // 2. 手动遍历 Range 补充表格内容（WordExtractor 有时漏掉表格内文字）
            try {
                Range range = doc.getRange();
                System.out.println("range = " + range.text());
                int tableCount = 0;
                StringBuilder tableText = new StringBuilder();
                for (int i = 0; i < range.numParagraphs(); i++) {
                    Paragraph para = range.getParagraph(i);
                    if (para.isInTable()) {
                        Table table = range.getTable(para);
                        tableCount++;
                        for (int r = 0; r < table.numRows(); r++) {
                            TableRow row = table.getRow(r);
                            for (int c = 0; c < row.numCells(); c++) {
                                TableCell cell = row.getCell(c);
                                String cellText = cell.text();
                                if (cellText != null && !cellText.isBlank()) {
                                    tableText.append(cellText).append("\n");
                                }
                            }
                        }
                    }
                }
                if (tableText.length() > 0) {
                    sb.append("\n\n【表格内容】\n").append(tableText);
                    log.info("[解析-POI] .doc 补充提取了 {} 个表格，文本长度={}", tableCount, tableText.length());
                }
            } catch (Exception e) {
                log.warn("[解析-POI] 表格提取异常: {}", e.getMessage());
            }

            String result = sb.toString();
            log.info("[解析-POI] .doc 最终文本长度={}", result.length());
            return result;
        }
    }

    /**
     * 用 POI XWPFWordExtractor 解析 .docx 文件
     */
    private String parseDocxWithPOI(byte[] bytes) throws IOException {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(bytes))) {
            XWPFWordExtractor extractor = new XWPFWordExtractor(doc);
            String text = extractor.getText();
            log.info("[解析-POI] .docx XWPFWordExtractor 文本长度={}", text.length());
            return text;
        }
    }

    /**
     * 用 Tika 解析其他格式（PDF、TXT、MD、HTML）
     */
    private String parseWithTika(InputStream in) throws IOException, TikaException, SAXException {
        BodyContentHandler handler = new BodyContentHandler(-1);
        Metadata metadata = new Metadata();
        ParseContext context = new ParseContext();
        context.set(Parser.class, autoDetectParser);
        autoDetectParser.parse(in, handler, metadata, context);
        return handler.toString();
    }

    /** 摄入异常 */
    public static class IngestionException extends RuntimeException {
        public IngestionException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
