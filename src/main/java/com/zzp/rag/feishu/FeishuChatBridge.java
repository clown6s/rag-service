package com.zzp.rag.feishu;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zzp.rag.agent.AgentOrchestrator;
import com.zzp.rag.audit.AuditEvent;
import com.zzp.rag.audit.AuditService;
import com.zzp.rag.web.StreamTokenFixer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 飞书对话桥（渠道适配器核心）：飞书消息 → 数字员工对话 → 回复。
 * <p>
 * 两个入口共用一个处理核心：
 * - SDK 长连接（WS）事件 → {@link #handleMessage(String, String, String, String)}
 * - HTTP 回调事件（JsonNode）→ {@link #handleEvent(JsonNode)} 拆包后调同一核心
 * <p>
 * 设计要点：
 * - conversationId = chat_id（每个群/单聊一份会话记忆，与 Web 端隔离）
 * - agentId 默认取配置（数字员工绑定：人设/知识范围/技能授权全链路生效）
 * - 审计记录 endpoint=feishu/chat（所有渠道一个审计真相源，合规要求）
 * - 软依赖：任何一步失败只记日志，尽力回复兜底文案，绝不让回调挂掉
 */
@Slf4j
@Component
public class FeishuChatBridge {

    private final FeishuConfig config;
    private final FeishuClient client;
    private final AgentOrchestrator orchestrator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;

    /** 对话生成要几十秒，事件处理必须异步（SDK ack 后飞书才不重投）；按 messageId 去重兜底重投 */
    private final java.util.concurrent.ExecutorService worker =
            java.util.concurrent.Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "feishu-chat");
                t.setDaemon(true);
                return t;
            });
    private final java.util.concurrent.ConcurrentHashMap<String, Long> seenMessageIds =
            new java.util.concurrent.ConcurrentHashMap<>();

    public FeishuChatBridge(FeishuConfig config, FeishuClient client,
                            AgentOrchestrator orchestrator, AuditService auditService,
                            ObjectMapper objectMapper) {
        this.config = config;
        this.client = client;
        this.orchestrator = orchestrator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** HTTP 回调模式入口：schema 2.0 事件体 → 拆包 → 处理核心 */
    public void handleEvent(JsonNode event) {
        JsonNode message = event.path("event").path("message");
        handleMessage(
                message.path("chat_id").asText(),
                message.path("message_id").asText(),
                message.path("message_type").asText(),
                message.path("content").asText());
    }

    /** SDK 长连接/HTTP 共用的处理核心：立即返回（事件 ack），处理走工作线程 + messageId 去重 */
    public void handleMessage(String chatId, String messageId, String messageType, String contentJson) {
        if (messageId == null || messageId.isBlank()) {
            return;
        }
        Long prev = seenMessageIds.putIfAbsent(messageId, System.currentTimeMillis());
        if (prev != null) {
            log.info("[飞书] 重复消息跳过（重投） messageId={}", messageId);
            return;
        }
        if (seenMessageIds.size() > 2000) {
            seenMessageIds.clear();   // 兜底防膨胀（正常不会到：消息 id 短时窗口去重）
        }
        worker.execute(() -> doHandle(chatId, messageId, messageType, contentJson));
    }

    private void doHandle(String chatId, String messageId, String messageType, String contentJson) {
        AuditEvent.AuditContext ctx = AuditEvent.AuditContext.capture();
        String query = null;
        long start = System.currentTimeMillis();
        try {
            if (!"text".equals(messageType) || chatId == null || chatId.isBlank()) {
                log.info("[飞书] 跳过非文本消息 chat={} type={}", chatId, messageType);
                return;
            }
            query = parseText(contentJson);
            if (query.isBlank()) {
                log.info("[飞书] 文本为空，跳过 chat={}", chatId);
                return;
            }
            log.info("[飞书] 收到消息 chat={} query={}", chatId, query);

            // 数字员工对话：conversationId=chat_id（按群隔离记忆）；agentId 默认绑定
            Long agentId = config.defaultAgentId() > 0 ? config.defaultAgentId() : null;
            AgentOrchestrator.AgentAnswer answer = orchestrator.answer(chatId, query, agentId);
            String text = toFeishuText(StreamTokenFixer.fixFull(answer.answer()));
            boolean replied = client.replyMessage(messageId, text);
            if (!replied) {
                log.warn("[飞书] 回复失败 chat={} messageId={}", chatId, messageId);
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "feishu/chat",
                    null, query, 0, answer.sources(), 0,
                    answer.rerankDegraded(), answer.graphContextChars(),
                    AuditEvent.truncate(answer.answer(), 500), answer.answer().length(),
                    System.currentTimeMillis() - start, "SUCCESS", null));
        } catch (Exception e) {
            log.error("[飞书] 消息处理失败 chat={}", chatId, e);
            if (messageId != null) {
                client.replyMessage(messageId, "抱歉，处理您的消息时出错了，请稍后再试。");
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    ctx.user(), ctx.ip(), "feishu/chat",
                    null, query, 0, List.of(), 0,
                    false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage()));
        }
    }

    /** 提取文本并剥掉 @机器人 提及（文本消息里 @ 以 @_user_1 形式出现） */
    private String parseText(String contentJson) {
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            String text = node.path("text").asText(null);
            if (text == null) {
                return "";
            }
            return text.replaceAll("@_user_\\d+", "").replaceAll("\\s+", " ").strip();
        } catch (Exception e) {
            log.debug("[飞书] content 解析失败: {}", e.getMessage());
            return "";
        }
    }

    /** Markdown → 飞书纯文本（text 消息不支持 markdown 渲染）：
     *  剥加粗/斜体星号，## 标题转【】，保留有序编号与换行 */
    private String toFeishuText(String markdown) {
        return markdown
                .replaceAll("(?m)^##\\s+(.+)$", "【$1】")
                .replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1")
                .replaceAll("(?m)^[\\s>]*>\\s*", "")
                .strip();
    }
}
