package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 法律条款切分模板 — 按"第X条"边界切分，每条独立 chunk，并注入章节归属
 * <p>
 * 层级结构（借鉴 RAGFlow tree_merge 思路的轻量版）：
 * 编/篇/部分（层级1）→ 章（层级2）→ 节（层级3）→ 条。
 * 切分边界 = 编/篇/章/节/条 全部；条款 chunk 注入当前章节路径前缀
 * （如 [章节: 第一编 总则 / 第三章 刑罚]），章节标题本身也作为独立 chunk
 * （检索"第三章"可直接命中）；解决 BM25 分词"第五条"误匹配"第五十四条"。
 */
public class LawsTemplate implements ChunkTemplate {

    /** 至少 N 条"第X条"才按条款切分（避免 Q&A 文档等误触发） */
    private static final int MIN_ARTICLES = 5;

    /**
     * 边界：编/篇/部分/章/节/条 —— 锚定行首（句中"违反本法第二条规定的"的引用不是边界）。
     * 捕获组(1) 定位"第"的真实位置：^\s* 会吃前导换行，若用 m.start() 会指向换行符
     * （导致 headingLine 取空行、层级栈标题为空），必须用 start(1)/group(1)。
     */
    private static final Pattern BOUNDARY = Pattern.compile(
            "(?m)^\\s*(第[一二三四五六七八九十百千零\\d]+(?:部分|编|篇|章|节|条))");

    /** 章节标题（非条款），同样锚定行首 + 捕获组 */
    private static final Pattern HEADING = Pattern.compile(
            "(?m)^\\s*(第[一二三四五六七八九十百千零\\d]+(?:部分|编|篇|章|节))");

    private final int maxSize;
    private final SentenceSplitter splitter;

    public LawsTemplate(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.splitter = new SentenceSplitter(maxSize, overlap);
    }

    /** 层级栈元素 */
    private record Heading(int level, String title) {}

    @Override
    public String name() {
        return "laws";
    }

    @Override
    public boolean matches(ParsedDocument parsed) {
        int count = 0;
        Matcher m = BOUNDARY.matcher(parsed.text());
        while (m.find()) {
            count++;
        }
        return count >= MIN_ARTICLES;
    }

    @Override
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary) {
        String text = parsed.text();
        String prefix = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n";

        Matcher m = BOUNDARY.matcher(text);
        List<Integer> positions = new ArrayList<>();
        List<String> markers = new ArrayList<>();
        while (m.find()) {
            positions.add(m.start(1));   // "第" 的真实位置（不含被 \s* 吃掉的前导换行）
            markers.add(m.group(1));
        }
        // 不足 2 条边界，回退通用切分（防御，matches 已保证）
        if (positions.size() < 2) {
            return splitter.split(Document.from(prefix + text));
        }

        List<TextSegment> segments = new ArrayList<>();

        // 第一个边界之前的内容（文档标题、序言等）保留为 chunk
        String preText = text.substring(0, positions.get(0)).trim();
        if (!preText.isEmpty()) {
            segments.addAll(splitter.split(Document.from(prefix + preText)));
        }

        List<Heading> stack = new ArrayList<>();
        for (int i = 0; i < positions.size(); i++) {
            int start = positions.get(i);
            int end = (i + 1 < positions.size()) ? positions.get(i + 1) : text.length();
            String chunk = text.substring(start, end).trim();
            if (chunk.isEmpty()) {
                continue;
            }

            if (HEADING.matcher(markers.get(i)).matches()) {
                // 章节标题：更新层级栈（移除 >= 当前层级的旧标题），标题 chunk 单独入库
                int level = headingLevel(markers.get(i));
                String title = headingLine(text, start);
                stack.removeIf(h -> h.level() >= level);
                stack.add(new Heading(level, title));
                emit(chunk, joinPath(stack), prefix, segments);
            } else {
                // 条款：注入当前章节路径
                emit(chunk, stack.isEmpty() ? "" : joinPath(stack), prefix, segments);
            }
        }
        return segments;
    }

    /** 生成 chunk（超长递归切分），section 元数据 = 章节路径 */
    private void emit(String chunk, String sectionPath, String prefix, List<TextSegment> segments) {
        String sectionPrefix = sectionPath.isEmpty()
                ? "" : "[章节: " + sectionPath + "]\n\n";
        String full = prefix + sectionPrefix + chunk;
        if (chunk.length() > maxSize) {
            for (TextSegment seg : splitter.split(Document.from(full))) {
                seg.metadata().put("section", sectionPath);
                segments.add(seg);
            }
        } else {
            TextSegment seg = TextSegment.from(full);
            seg.metadata().put("section", sectionPath);
            segments.add(seg);
        }
    }

    /** 层级：编/篇/部分=1，章=2，节=3 */
    private int headingLevel(String marker) {
        if (marker.endsWith("部分") || marker.endsWith("编") || marker.endsWith("篇")) {
            return 1;
        }
        if (marker.endsWith("章")) {
            return 2;
        }
        return 3;
    }

    /** 取标题整行（含章节名，如"第三章 刑罚"） */
    private String headingLine(String text, int pos) {
        int end = text.indexOf('\n', pos);
        return text.substring(pos, end > 0 ? end : text.length()).trim();
    }

    private String joinPath(List<Heading> stack) {
        StringBuilder sb = new StringBuilder();
        for (Heading h : stack) {
            if (sb.length() > 0) {
                sb.append(" / ");
            }
            sb.append(h.title());
        }
        return sb.toString();
    }
}
