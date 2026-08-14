package com.zzp.rag.web;

/**
 * 流式输出格式修复器 — 后端 token 级兜底，不依赖前端 fixMarkdown
 * <p>
 * 由固定流水线端点（RagChatController）与 Agent 端点（AgentChatController）共用，
 * 保证两条链路格式行为一致。
 */
public final class StreamTokenFixer {

    private StreamTokenFixer() {}

    /**
     * 处理一个流式 token（含跨 token 修复）：
     * 1. 缓冲区末尾不是换行且 token 以 数字.+中文 开头时补换行
     * 2. token 级格式修复（见 {@link #fixToken}）
     */
    public static String fix(String token, StringBuilder fullAnswer) {
        if (token == null || token.isEmpty()) return token;
        String result = token;
        if (fullAnswer.length() > 0) {
            char lastChar = fullAnswer.charAt(fullAnswer.length() - 1);
            if (lastChar != '\n') {
                String trimmed = result.stripLeading();
                if (trimmed.matches("\\d+\\.[\\u4e00-\\u9fa5a-zA-Z（].*")) {
                    result = "\n" + trimmed;
                }
            }
        }
        return fixToken(result);
    }

    /** 整段答案修复（非流式端点用）：token 级修复 + 子标题/列表规范化 */
    public static String fixFull(String text) {
        if (text == null) return text;
        String result = sanitizeCitations(fixToken(text));
        // 0. 装饰性 emoji 剥除——模型的分节/强调标记漂移（✅📌⚠️🩺🌐📜⚖️🧩🔍🌟🎯💡💊❗），
        //    语义由加粗承担；行首行内都剥（这些符号在本平台答案里纯装饰，无信息量）
        result = result.replaceAll("[✅📌⚠️🩺🌐📜⚖️🧩🔍🌟🎯💡💊❗⏳⌛]+\\s*", "");
        // 0.05 整行加粗的标题尾部空格塌缩（"**标题  **" 的收尾空格会让 CommonMark
        //      不识别闭合 → 原样显示星号）
        result = result.replaceAll("(?m)^(\\*\\*[^*\\n]+?)\\s+(\\*\\*)[ \\t]*$", "$1$2");
        // 0.06 硬删后空壳清理："（如、等）"——校验层删除术语后残留的空枚举壳
        result = result.replaceAll("[（(]\\s*如\\s*[、，]?\\s*等\\s*[）)]", "");
        result = result.replaceAll("如\\s*[、，]\\s*等", "");
        // 0.1 "---### 标题" 粘连 → "### 标题"（LLM 常把分隔线写进标题行，直接破坏渲染）
        result = result.replaceAll("(?m)^-{2,}\\s*(#{1,3})(?=\\s)", "$1");
        // 1. ### 子标题 → 加粗段落（规则只允许一个 ## 主标题）
        result = result.replaceAll("(?m)^#{3,}\\s*(.+)$", "**$1**");
        // 1.5 ▶ 内联/行首列表符 → 先转无序行，再随下一步统一有序编号
        //     （"原则：▶ 小剂量 ▶ 长效" → 拆行编号；须在 convertBulletsToNumbered 之前）
        result = result.replaceAll("\\s*▶\\s*", "\n- ");
        // 1.6 "注意： -高血压急症" 行中列表符 → 断行成列表项（须在编号转换之前）
        result = result.replaceAll("(?<=[：:])\\s*-\\s*(?=[^\\s])", "\n- ");
        // 1.7 行中编号断行："**标题**1. 内容" → 编号项前补换行
        //     （模型把列表起点粘在标题/正文后；"N. " 后跟中文/字母才算列表项）
        result = result.replaceAll("(?<=[^\\s\\d])\\s*(?=\\d+\\.\\s+[\\u4e00-\\u9fa5a-zA-Z（])", "\n");
        // 1.8 标签粘连断行："测量部位：上臂肱动脉 测量场所：诊所" → 短标签（≤8 字）前换行。
        //     前导约束：标签前必须是空格（且空格前不是数字/点号，防拆"4. 眼："这类编号项）
        //     或句末标点——避免把词内部的"工具："（测量工具）误断
        result = result.replaceAll(
                "(?<=(?<![.\\d])\\s|[。；！？）】\\n])\\s*(?=[\\u4e00-\\u9fa5]{1,8}：)", "\n");
        // 2. - / * / 🔹 无序列表 → 有序列表（规则要求 1. 2. 3.；全行加粗的章节标题行不参与编号）
        result = convertBulletsToNumbered(result);
        // 2.5 全行加粗的编号行（模型的编号章节标题，如 "9. **关联罪名**"）→ 剥编号还原加粗标题
        result = result.replaceAll("(?m)^\\d+\\.\\s*(\\*\\*[^*\\n]+\\*\\*)\\s*$", "$1");
        // 2.6 编号连续性修正：模型自编号与转换器编号混用导致跨节连号
        //     （"关联罪名"节从 2. 开始）→ 按连续行段重新编号（非编号行/空行打断则重置）
        result = renumberNumberedLists(result);
        // 3. 引用块标记（提示词禁了但模型仍输出，行首及句号后的 > 直接剥除）
        result = result.replaceAll("(?m)^>+\\s*", "");
        result = result.replaceAll("(?<=[。！？；])>\\s*", "");
        // 4. 连续冒号塌缩（"：："是模型常见手滑）
        result = result.replaceAll("：{2,}", "：");
        // 5. 未闭合的加粗行（行首 ** 且行内无任何收尾 *）→ 剥星号按普通文本
        //    （无长度限制；历史坑：60 字上限漏掉"**标题1.诊断标准：…"这类长标题行）
        result = result.replaceAll("(?m)^\\*\\*([^*\\n]+)$", "$1");
        // 6. 句末标点/右括号后紧跟编号项 → 换行（"…测量）。 2. 测量工具"、
        //    "（企业参考）1."入户抢劫"" 拆成两行）
        result = result.replaceAll("(?<=[。！？；）)])\\s*(?=\\d+[.、])", "\n");
        // 7. 标题与表格粘连（"⚠️ 标题| 列1 | 列2 |"同行）→ 首个 | 前换行
        result = result.replaceAll("(?m)^([^|\\n]{1,60}?)(\\s*\\|.*\\|.*)$", "$1\n$2");
        // 8. 表格行之间的空行塌缩：模型在表格行间插空行，GFM 表格会拆散成多段
        //    （"| 人群 |...|\n\n|--- |" → 连续行）
        result = result.replaceAll("(?m)\\|[ \\t]*\\n[ \\t]*\\n(?=[ \\t]*\\|)", "|\n");
        // 9. 表格净化：残缺表格残留是渲染重灾区（"| |------ |------| |" 纯语法噪声）——
        //    a. 分隔行（仅 | - : 空格）整行删除
        //    b. 不成表的行（缺行首/行尾管道）→ 剥管道降级为文本行
        //    c. 成表行 → 单元格归一化（| 类型 | 特点 |）
        result = sanitizeTables(result);
        return result;
    }

    /** 表格净化：删分隔行、不成表降级文本、成表归一化、内联粘连表格拆行（详见 fixFull 规则 9） */
    private static String sanitizeTables(String text) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.isEmpty() || !stripped.contains("|")) {
                out.add(line);
                continue;
            }
            // 内联粘连表格："| 类型 | 特点 | |--- |--- | | 继发 | ... |"（表头+分隔+数据行挤一行）
            // → 先按分隔单元格拆成多行再走常规净化
            if (stripped.contains("---") && !stripped.matches("[\\s|:\\-]+")) {
                out.addAll(java.util.List.of(splitInlineTable(stripped).split("\n", -1)));
                continue;
            }
            if (stripped.contains("-") && stripped.matches("[\\s|:\\-]+")) {
                out.add("");   // 纯分隔行（|--- |---| 等）
                continue;
            }
            boolean wellFormed = stripped.startsWith("|") && stripped.endsWith("|");
            if (!wellFormed) {
                out.add(stripped.replace("|", " ").replaceAll("\\s{2,}", " ").strip());
            } else {
                out.add(stripped
                        .replaceAll("\\s*\\|\\s*", " | ")
                        .replaceAll("^\\|\\s*", "| ")
                        .replaceAll("\\s*\\|$", " |"));
            }
        }
        return String.join("\n", out);
    }

    /**
     * 内联粘连表格拆行：按单元格扫描，分隔单元格（---）起一行、内容单元格起一行。
     * "| A | B | |--- |--- | | C | D |" → "| A | B |\n|--- |--- |\n| C | D |"
     */
    private static String splitInlineTable(String stripped) {
        StringBuilder sb = new StringBuilder();
        StringBuilder row = new StringBuilder();
        boolean inSeparator = false;
        for (String cell : stripped.split("\\|", -1)) {
            String c = cell.strip();
            boolean sep = c.contains("-") && c.matches("[-:]+");
            if (sep) {
                if (!inSeparator) {
                    if (row.length() > 0) {
                        sb.append(row).append("|\n");
                    }
                    row.setLength(0);
                    inSeparator = true;
                }
                row.append("| ").append(c).append(" ");
            } else if (!c.isEmpty()) {
                if (inSeparator) {
                    if (row.length() > 0) {
                        sb.append(row).append("|\n");
                    }
                    row.setLength(0);
                    inSeparator = false;
                }
                row.append("| ").append(c).append(" ");
            }
        }
        if (row.length() > 0) {
            sb.append(row).append("|");
        }
        return sb.toString().strip();
    }

    /** 把行首 `- ` / `* ` / `🔹 ` 的无序列表按出现顺序转为 `N. ` 有序列表
     *  （🔹 是模型新漂移：提示词禁了 -/* 后改用 emoji 当列表符）
     *  全行加粗的列表项（"- **章节标题**"）是模型的分节标题而非列表项——剥掉列表符当标题行，
     *  不参与编号，并打断编号连续。 */
    private static String convertBulletsToNumbered(String text) {
        String[] lines = text.split("\n", -1);
        int counter = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            String indent = line.substring(0, line.length() - stripped.length());
            if ((stripped.startsWith("- **") || stripped.startsWith("* **")) && stripped.endsWith("**")) {
                counter = 0;
                lines[i] = indent + stripped.substring(2).strip();
            } else if (stripped.startsWith("- ") || stripped.startsWith("* ")
                    || stripped.startsWith("🔹 ") || stripped.startsWith("→ ")
                    || stripped.startsWith("• ")) {
                counter++;
                lines[i] = indent + counter + ". " + stripped.substring(2);
            } else if (!stripped.isEmpty()) {
                counter = 0; // 列表被非列表行打断则重新计数
            }
        }
        return String.join("\n", lines);
    }

    /** token 级格式修复 */
    public static String fixToken(String token) {
        if (token == null || token.isEmpty()) return token;
        String result = token;
        // 0. 全角/数学变体归一：∗（U+2217 数学星号，模型常当加粗标记用）→ ASCII *；
        //    ＄（U+FF04 全角美元）→ $（LaTeX 定界符）
        result = result.replace('∗', '*').replace('＄', '$');
        // 1. 破折号 – — 替换为 ：
        result = result.replaceAll("[\\u2013\\u2014]", "：");
        // 2. 数字列表挤在同一行： "象1.岁" → "象\n1. 岁"
        result = result.replaceAll("([^\\n\\s])\\s*(\\d+)\\.(?!\\s)([^\\s\\d])", "$1\n$2. $3");
        // 3. 数字列表符后补空格： "1.岁" → "1. 岁"
        result = result.replaceAll("(\\d+)\\.(?!\\s)([^\\s\\d])", "$1. $2");
        // 4. ##后补空格： "##标题" → "## 标题"
        result = result.replaceAll("(#{2,3})([^\\s#\\n])", "$1 $2");
        // 5. 无序列表 - 后补空格： "-岁" → "- 岁"
        result = result.replaceAll("-(?!\\s)([^\\s\\d-])", "- $1");
        return result;
    }

    /** 修复 LLM 输出中漏闭合的来源标签 [来源N → [来源N] */
    public static String sanitizeCitations(String text) {
        if (text == null) return text;
        return text.replaceAll("\\[来源(\\d+)(?![\\]])", "[来源$1]");
    }

    /**
     * 编号连续性修正：行首 `N. ` 的连续行段按 1..N 重排，
     * 非编号行（标题/正文/空行/（一）式枚举）打断段并重置计数。
     * 只改编号本身，条目内容原样保留。
     */
    private static String renumberNumberedLists(String text) {
        String[] lines = text.split("\n", -1);
        java.util.regex.Pattern num = java.util.regex.Pattern.compile("^(\\d+)\\.\\s+(.*)$");
        // 双重编号：模型把条文枚举"（一）"套在自编号里（"1. （一）入户抢劫"）→ 剥中文数字
        java.util.regex.Pattern dup = java.util.regex.Pattern.compile(
                "^(\\d+)\\.\\s*（[一二三四五六七八九十]+）\\s*(.*)$");
        // 按缩进层级分别计数：嵌套列表（外层 1. → 子层 1./2. → 回外层应为 2.）
        java.util.Map<String, Integer> counters = new java.util.HashMap<>();
        String lastIndent = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            String indent = line.substring(0, line.length() - stripped.length());
            java.util.regex.Matcher dm = dup.matcher(stripped);
            if (dm.matches()) {
                int c = counters.getOrDefault(indent, 0) + 1;
                counters.put(indent, c);
                counters.keySet().removeIf(k -> k.length() > indent.length());   // 更深层计数失效
                lastIndent = indent;
                lines[i] = indent + c + ". " + dm.group(2);
                continue;
            }
            java.util.regex.Matcher m = num.matcher(stripped);
            if (m.matches()) {
                int c = counters.getOrDefault(indent, 0) + 1;
                counters.put(indent, c);
                counters.keySet().removeIf(k -> k.length() > indent.length());
                lastIndent = indent;
                lines[i] = indent + c + ". " + m.group(2);
            } else if (stripped.isEmpty()) {
                counters.clear();     // 空行 = 段间分隔，重置
                lastIndent = null;
            } else if (lastIndent == null || indent.length() > lastIndent.length()) {
                // 更深缩进的非编号行 = 上一列表项的续行（条目内容折行），不打断编号
                // （如 "1. 民法责任：\n  ...续行段落...\n1. 转化型抢劫" 的第二个 1. 应改为 2.）
            } else {
                counters.clear();     // 同级/更浅的非编号行 = 新的正文段落，重置
                lastIndent = null;
            }
        }
        return String.join("\n", lines);
    }
}
