package com.zzp.rag.ingestion.chunking;

import com.zzp.rag.ingestion.ParsedDocument;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Q&A 文档切分模板 — 每个问答对 = 一个独立 chunk
 * <p>
 * 问题模式移植自 RAGFlow rag/nlp/__init__.py 的 QUESTION_PATTERN 与 not_bullet
 * （Apache License 2.0），精简为中文常用模式；chunk 内容格式沿用其
 * "问题：Q / 回答：A" 成对结构（rag/app/qa.py）。
 * <p>
 * 适用场景：导游面试问答、FAQ 手册等"一文档多主题"的问答集——
 * 这类文档按句子切分会把问答对切碎，是检索精度（context_precision）的主要噪声源。
 */
public class QaTemplate implements ChunkTemplate {

    /** 最少问题命中数才启用本模板（与法律条款切分阈值策略一致，避免普通文档误触发） */
    private static final int MIN_QUESTION_HITS = 5;

    /**
     * 问答对结构校验：相邻题号行的平均行间距上限。
     * QA 文档题号紧凑（题号之间通常 1-5 行）；技术书/教材的公式、习题编号稀疏
     * （间隔几十行推导），误触发过《南瓜书》（模板=qa 1174 chunks，全是碎片）。
     */
    private static final int MAX_AVG_GAP = 8;

    private static final Pattern[] QUESTION_PATTERNS = {
            Pattern.compile("^第([零一二三四五六七八九十百0-9]+)问[、. :：]*"),
            Pattern.compile("^第([0-9]+)问[、. :：]*"),
            Pattern.compile("^[（(]([零一二三四五六七八九十百]+)[)）][、. :：]*"),
            Pattern.compile("^[（(]([0-9]{1,2})[)）][、. :：]*"),
            Pattern.compile("^([零一二三四五六七八九十百]+)[、 ][  ]*"),
            Pattern.compile("^([0-9]{1,2})[.、 ][  ]*"),
    };

    /** 排除误判：纯数字行、页码、"数字 个单位"等（对应 RAGFlow 的 not_bullet） */
    private static final Pattern NOT_BULLET = Pattern.compile(
            "^(0$|[0-9]+ +[0-9~个只-]|[0-9]+\\.{2,}|[0-9]+(\\.[0-9]+){2,})");

    /** "第X条"归 LawsTemplate，不视为问答题号 */
    private static final Pattern ARTICLE = Pattern.compile("^第[零一二三四五六七八九十百0-9]+条");

    private final int maxSize;
    private final SentenceSplitter splitter;

    public QaTemplate(int maxSize, int overlap) {
        this.maxSize = maxSize;
        this.splitter = new SentenceSplitter(maxSize, overlap);
    }

    @Override
    public String name() {
        return "qa";
    }

    @Override
    public boolean matches(ParsedDocument parsed) {
        int hits = 0;
        int gapSum = 0;
        int lastHitLine = -1;
        String[] lines = parsed.text().split("\n");
        for (int i = 0; i < lines.length; i++) {
            String s = lines[i].strip();
            if (s.isEmpty() || NOT_BULLET.matcher(s).find() || ARTICLE.matcher(s).find()) {
                continue;
            }
            for (Pattern p : QUESTION_PATTERNS) {
                if (p.matcher(s).find()) {
                    hits++;
                    if (lastHitLine >= 0) {
                        gapSum += (i - lastHitLine);
                    }
                    lastHitLine = i;
                    break;
                }
            }
        }
        if (hits < MIN_QUESTION_HITS) {
            return false;
        }
        // 结构校验：题号平均间距（问答对紧凑，编号类书籍稀疏）
        double avgGap = hits > 1 ? (double) gapSum / (hits - 1) : Double.MAX_VALUE;
        return avgGap <= MAX_AVG_GAP;
    }

    @Override
    public List<TextSegment> chunk(ParsedDocument parsed, String fileName, String docSummary) {
        String prefix = "[文档: " + fileName + " | 摘要: " + docSummary + "]\n\n";
        List<TextSegment> all = new ArrayList<>();
        StringBuilder preamble = new StringBuilder();
        StringBuilder currentQ = new StringBuilder();
        StringBuilder currentA = new StringBuilder();

        for (String rawLine : parsed.text().split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty()) {
                continue;
            }
            if (isQuestion(line)) {
                flush(prefix, currentQ, currentA, all);
                currentQ = new StringBuilder(stripBullet(line));
                currentA = new StringBuilder();
            } else if (currentQ.isEmpty()) {
                preamble.append(line).append("\n");   // 首个问题之前的序言
            } else {
                currentA.append(line).append("\n");
            }
        }
        flush(prefix, currentQ, currentA, all);

        // 序言作为独立部分切分（保留文档开头信息）
        if (!preamble.isEmpty()) {
            all.addAll(0, splitter.split(Document.from(prefix + preamble)));
        }
        return all;
    }

    private boolean isQuestion(String line) {
        if (NOT_BULLET.matcher(line).find() || ARTICLE.matcher(line).find()) {
            return false;
        }
        for (Pattern p : QUESTION_PATTERNS) {
            if (p.matcher(line).find()) {
                return true;
            }
        }
        return false;
    }

    /** 去掉题号前缀（如 "16、" → 无），保留问题正文 */
    private String stripBullet(String line) {
        for (Pattern p : QUESTION_PATTERNS) {
            Matcher m = p.matcher(line);
            if (m.find()) {
                return line.substring(m.end()).strip();
            }
        }
        return line;
    }

    /** 一个问答对落盘：超长时递归切分（罕见），保持"问题：Q / 回答：A"结构 */
    private void flush(String prefix, StringBuilder q, StringBuilder a, List<TextSegment> all) {
        if (q.isEmpty()) {
            return;
        }
        String text = prefix + "问题：" + q + "\n回答：\n" + a.toString().trim();
        if (text.length() <= maxSize) {
            TextSegment seg = TextSegment.from(text);
            seg.metadata().put("qa_question", q.toString());
            all.add(seg);
        } else {
            for (TextSegment seg : splitter.split(Document.from(text))) {
                seg.metadata().put("qa_question", q.toString());
                all.add(seg);
            }
        }
    }
}
