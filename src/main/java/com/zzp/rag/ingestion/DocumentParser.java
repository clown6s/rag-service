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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 文档解析器（双通道）
 * <p>
 * .doc  → Apache POI HWPFDocument + WordExtractor（直接底层提取，比 Tika 更完整）
 * .docx → Apache POI XWPFDocument + XWPFWordExtractor
 * .pdf  → ai-sidecar /parse（PyMuPDF 文本提取 + 扫描件检测），失败/未部署降级 Tika（软依赖）
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
    private final RestTemplate restTemplate;

    @Value("${rag.parse.sidecar-url:http://127.0.0.1:8081}")
    private String sidecarUrl;

    @Value("${rag.parse.sidecar-token:${AISIDECAR_TOKEN:}}")
    private String sidecarToken;

    public DocumentParser() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(120_000);   // PDF 大文件解析较慢，放宽读超时
        this.restTemplate = new RestTemplate(factory);
    }

    /**
     * 从上传文件提取结构化解析结果（全量提取，不截断）
     */
    public ParsedDocument parse(MultipartFile file) {
        String fileName = file.getOriginalFilename();
        String text = "";
        List<String> warnings = new ArrayList<>();
        List<ParsedDocument.ParsedSection> sections = new ArrayList<>();

        try {
            if (fileName != null && fileName.toLowerCase().endsWith(".doc")
                    && !fileName.toLowerCase().endsWith(".docx")) {
                // .doc 文件用 POI WordExtractor 直接提取（sidecar 不支持 .doc）
                text = parseDocWithPOI(file.getBytes());
            } else if (fileName != null && fileName.toLowerCase().endsWith(".docx")) {
                // docx 双通道：优先 sidecar /parse（标题层级结构提取），失败降级 POI
                try {
                    ParsedDocument parsed = parseWithSidecar(file.getBytes(), fileName);
                    text = parsed.text();
                    warnings.addAll(parsed.warnings());
                    sections = parsed.sections();
                } catch (Exception e) {
                    log.warn("[解析] sidecar /parse 失败，降级 POI: {}", e.getMessage());
                    text = parseDocxWithPOI(file.getBytes());
                }
            } else if (fileName != null && fileName.toLowerCase().endsWith(".pdf")) {
                // PDF 双通道：优先 sidecar /parse（含扫描件检测），失败降级 Tika
                try {
                    ParsedDocument parsed = parseWithSidecar(file.getBytes(), fileName);
                    text = parsed.text();
                    warnings.addAll(parsed.warnings());
                    sections = parsed.sections();
                } catch (Exception e) {
                    log.warn("[解析] sidecar /parse 失败，降级 Tika: {}", e.getMessage());
                    text = parseWithTika(file.getInputStream());
                }
            } else if (fileName != null && isNewSidecarType(fileName)) {
                // PPT/表格 双通道：优先 sidecar /parse（pptx 标题层级、xlsx/csv 表格结构），
                // 失败降级 Tika（软依赖——Tika 也能提取 OOXML 文本，只是无结构）
                try {
                    ParsedDocument parsed = parseWithSidecar(file.getBytes(), fileName);
                    text = parsed.text();
                    warnings.addAll(parsed.warnings());
                    sections = parsed.sections();
                } catch (Exception e) {
                    log.warn("[解析] sidecar /parse 失败，降级 Tika: {}", e.getMessage());
                    text = parseWithTika(file.getInputStream());
                }
            } else {
                // 其他格式用 Tika
                text = parseWithTika(file.getInputStream());
            }

            for (String warning : warnings) {
                log.warn("[解析] 文件={} sidecar警告: {}", fileName, warning);
            }

            // 清理多余空白
            text = text.replaceAll("\\n{3,}", "\n\n").trim();

            String preview = text.length() > 500 ? text.substring(0, 500) : text;
            log.info("[解析] 文件={} 文本长度={} 章节={} 预览前500字=[{}]",
                    fileName, text.length(), sections.size(), preview);

            if (text.isBlank()) {
                log.warn("[解析] 文件={} 主解析为空，尝试 Tika fallback", fileName);
                try (InputStream in = file.getInputStream()) {
                    text = tika.parseToString(in);
                    text = text.replaceAll("\\n{3,}", "\n\n").trim();
                    log.info("[解析] fallback 文本长度={}", text.length());
                }
            }

            return new ParsedDocument(text, warnings, sections);
        } catch (Exception e) {
            log.error("[解析] 文件={} 解析失败", fileName, e);
            throw new IngestionException("文档解析失败: " + fileName, e);
        }
    }

    /** PPT/表格类新格式（sidecar 结构化解析） */
    private boolean isNewSidecarType(String fileName) {
        String lower = fileName.toLowerCase();
        return lower.endsWith(".pptx") || lower.endsWith(".xlsx") || lower.endsWith(".csv");
    }

    /**
     * 调 ai-sidecar /parse 解析（PDF/DOCX/PPTX/XLSX/CSV）
     * 返回结构化结果；PDF 的 sections 由 DeepDoc 布局识别就绪后填充
     */
    private ParsedDocument parseWithSidecar(byte[] bytes, String fileName) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (sidecarToken != null && !sidecarToken.isBlank()) {
            headers.setBearerAuth(sidecarToken);
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(bytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });
        HttpEntity<MultiValueMap<String, Object>> entity = new HttpEntity<>(body, headers);

        @SuppressWarnings("rawtypes")
        ResponseEntity<Map> resp = restTemplate.exchange(
                sidecarUrl + "/parse", HttpMethod.POST, entity, Map.class);

        @SuppressWarnings("unchecked")
        Map<String, Object> json = (Map<String, Object>) resp.getBody();
        if (json == null) {
            throw new IllegalStateException("sidecar /parse 响应为空");
        }
        String text = json.getOrDefault("text", "").toString();
        List<String> warnings = new ArrayList<>();
        Object warningsObj = json.get("warnings");
        if (warningsObj instanceof List<?> list) {
            for (Object w : list) {
                warnings.add(String.valueOf(w));
            }
        }
        List<ParsedDocument.ParsedSection> sections = new ArrayList<>();
        Object sectionsObj = json.get("sections");
        if (sectionsObj instanceof List<?> list) {
            for (Object s : list) {
                if (s instanceof Map<?, ?> raw) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> m = (Map<String, Object>) raw;
                    sections.add(new ParsedDocument.ParsedSection(
                            ((Number) m.getOrDefault("level", 1)).intValue(),
                            String.valueOf(m.getOrDefault("title", "")),
                            String.valueOf(m.getOrDefault("content", ""))));
                }
            }
        }
        log.info("[解析-sidecar] 文件={} 文本长度={} 章节={} 警告={}",
                fileName, text.length(), sections.size(), warnings.size());
        return new ParsedDocument(text, warnings, sections);
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
     * 用 Tika 解析其他格式（TXT、MD、HTML 及 PDF 降级路径）
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
