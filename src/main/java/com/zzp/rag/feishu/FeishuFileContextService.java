package com.zzp.rag.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 飞书转发文件的会话级上下文（TTL 30 分钟）。
 * <p>
 * 用户转发文件给机器人 → 解析后存这里（只留文本，字节不驻留）→ 后续提问自动注入文件内容作答；
 * 「入库」按 messageId+fileKey 重新下载字节走摄入管线；「清除文件」移除上下文。
 * 键 = chatId:user（与文档向导同约定，群聊多人互不干扰）。
 */
@Slf4j
@Component
public class FeishuFileContextService {

    public record FileCtx(String fileName, String content, String messageId, String fileKey,
                          String resourceType, long createdAt) {}

    private static final long TTL_MS = 30 * 60_000L;
    public static final int MAX_CONTENT_CHARS = 60_000;
    private static final int SNIPPET_CHARS = 6_000;

    private final ConcurrentHashMap<String, FileCtx> ctxs = new ConcurrentHashMap<>();

    public String key(String chatId, String user) {
        return chatId + ":" + (user == null || user.isBlank() ? "anon" : user);
    }

    public void put(String chatId, String user, String fileName, String content,
                    String messageId, String fileKey, String resourceType) {
        ctxs.put(key(chatId, user), new FileCtx(fileName, content, messageId, fileKey,
                resourceType == null ? "file" : resourceType, System.currentTimeMillis()));
        log.info("[飞书文件] 会话文件上下文已建立 chat={} file={} chars={} type={}",
                chatId, fileName, content.length(), resourceType);
    }

    /** 取上下文（过期自动清理） */
    public FileCtx get(String chatId, String user) {
        String k = key(chatId, user);
        FileCtx ctx = ctxs.get(k);
        if (ctx == null) {
            return null;
        }
        if (System.currentTimeMillis() - ctx.createdAt() > TTL_MS) {
            ctxs.remove(k);
            return null;
        }
        return ctx;
    }

    public void clear(String chatId, String user) {
        ctxs.remove(key(chatId, user));
    }

    /** 提示词注入块（无上下文返回空串）；长文件截取开头并注明 */
    public String promptBlock(String chatId, String user) {
        FileCtx ctx = get(chatId, user);
        if (ctx == null) {
            return "";
        }
        String snippet = ctx.content().length() > SNIPPET_CHARS
                ? ctx.content().substring(0, SNIPPET_CHARS)
                + "\n…（文件较长，已截取开头；如需具体段落请指出）"
                : ctx.content();
        return "【用户在本会话中发来的文件《" + ctx.fileName() + "》内容】\n"
                + snippet + "\n【/文件内容】\n\n";
    }
}
