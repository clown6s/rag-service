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
        // 0. "---### 标题" 粘连 → "### 标题"（LLM 常把分隔线写进标题行，直接破坏渲染）
        result = result.replaceAll("(?m)^-{2,}\\s*(#{1,3})(?=\\s)", "$1");
        // 1. ### 子标题 → 加粗段落（规则只允许一个 ## 主标题）
        result = result.replaceAll("(?m)^#{3,}\\s*(.+)$", "**$1**");
        // 2. - / * 无序列表 → 有序列表（规则要求 1. 2. 3.）
        result = convertBulletsToNumbered(result);
        return result;
    }

    /** 把行首 `- ` / `* ` 的无序列表按出现顺序转为 `N. ` 有序列表 */
    private static String convertBulletsToNumbered(String text) {
        String[] lines = text.split("\n", -1);
        int counter = 0;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String stripped = line.stripLeading();
            if (stripped.startsWith("- ") || stripped.startsWith("* ")) {
                counter++;
                String indent = line.substring(0, line.length() - stripped.length());
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
}
