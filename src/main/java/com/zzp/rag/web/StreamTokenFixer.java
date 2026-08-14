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
        // 0. 行首装饰性 emoji 剥除（✅/📌/⚠️/🩺/🌐/📜/⚖️/🧩 是模型的分节标记漂移，
        //    标题语义由加粗承担，emoji 保留只会干扰渲染与后续规则匹配；
        //    含 "**📜 标题" 这种 emoji 嵌在加粗开头的情况）
        result = result.replaceAll("(?m)^[✅📌⚠️🩺🌐📜⚖️🧩]+\\s*", "");
        result = result.replaceAll("(?m)^(\\*\\*)[✅📌⚠️🩺🌐📜⚖️🧩]+\\s*", "$1");
        // 0.05 整行加粗的标题尾部空格塌缩（"**标题  **" 的收尾空格会让 CommonMark
        //      不识别闭合 → 原样显示星号）
        result = result.replaceAll("(?m)^(\\*\\*[^*\\n]+?)\\s+(\\*\\*)[ \\t]*$", "$1$2");
        // 0.1 "---### 标题" 粘连 → "### 标题"（LLM 常把分隔线写进标题行，直接破坏渲染）
        result = result.replaceAll("(?m)^-{2,}\\s*(#{1,3})(?=\\s)", "$1");
        // 1. ### 子标题 → 加粗段落（规则只允许一个 ## 主标题）
        result = result.replaceAll("(?m)^#{3,}\\s*(.+)$", "**$1**");
        // 1.5 ▶ 内联/行首列表符 → 先转无序行，再随下一步统一有序编号
        //     （"原则：▶ 小剂量 ▶ 长效" → 拆行编号；须在 convertBulletsToNumbered 之前）
        result = result.replaceAll("\\s*▶\\s*", "\n- ");
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
        return result;
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
                    || stripped.startsWith("🔹 ") || stripped.startsWith("→ ")) {
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
