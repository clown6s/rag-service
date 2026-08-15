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
    private final com.zzp.rag.agent.DocWizardService docWizardService;
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
                            AgentOrchestrator orchestrator,
                            com.zzp.rag.agent.DocWizardService docWizardService,
                            AuditService auditService,
                            ObjectMapper objectMapper) {
        this.config = config;
        this.client = client;
        this.orchestrator = orchestrator;
        this.docWizardService = docWizardService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
    }

    /** HTTP 回调模式入口：schema 2.0 事件体 → 拆包 → 处理核心（sender open_id 作审计身份） */
    public void handleEvent(JsonNode event) {
        JsonNode message = event.path("event").path("message");
        String senderOpenId = event.path("event").path("sender")
                .path("sender_id").path("open_id").asText("");
        handleMessage(
                message.path("chat_id").asText(),
                message.path("message_id").asText(),
                message.path("message_type").asText(),
                message.path("content").asText(),
                senderOpenId);
    }

    /** SDK 长连接/HTTP 共用的处理核心：立即返回（事件 ack），处理走工作线程 + messageId 去重 */
    public void handleMessage(String chatId, String messageId, String messageType, String contentJson,
                              String senderOpenId) {
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
        worker.execute(() -> doHandle(chatId, messageId, messageType, contentJson, senderOpenId));
    }

    private void doHandle(String chatId, String messageId, String messageType, String contentJson,
                          String senderOpenId) {
        // 审计身份：飞书 open_id（无则回退 MDC/请求头/anonymous）
        String auditUser = senderOpenId != null && !senderOpenId.isBlank() ? senderOpenId : null;
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

            // 文档向导慢生成（确认生成/修改）先回"正在处理"，避免用户干等
            String wizardUser = auditUser;
            String wizardKey = chatId + ":" + (wizardUser == null ? "anon" : wizardUser);
            if (docWizardService.willGenerateSlow(wizardKey, query)) {
                client.replyMessage(messageId, "收到，正在生成文档，约需半分钟…");
            }
            // 数字员工对话：conversationId=chat_id（按群隔离记忆）；agentId 默认绑定。
            // 先把 open_id 写入 MDC：编排器里的向导 key 用 capture().user()，
            // 与下方 takePending 的 key 必须一致（历史坑：工作线程 MDC 为空导致文件漏发）
            String prevMdcUser = org.slf4j.MDC.get(AuditEvent.MDC_USER_KEY);
            if (auditUser != null) {
                org.slf4j.MDC.put(AuditEvent.MDC_USER_KEY, auditUser);
            }
            Long agentId = config.defaultAgentId() > 0 ? config.defaultAgentId() : null;
            AgentOrchestrator.AgentAnswer answer;
            try {
                answer = orchestrator.answer(chatId, query, agentId);
            } finally {
                if (prevMdcUser != null) {
                    org.slf4j.MDC.put(AuditEvent.MDC_USER_KEY, prevMdcUser);
                } else {
                    org.slf4j.MDC.remove(AuditEvent.MDC_USER_KEY);
                }
            }
            String markdown = StreamTokenFixer.fixFull(answer.answer());
            // 定稿文件推送（上传素材 + 发文件消息）
            com.zzp.rag.agent.DocWizardService.Pending pending =
                    docWizardService.takePending(wizardKey);
            if (pending != null) {
                String fileKey = client.uploadFile(pending.fileName(), pending.bytes());
                if (fileKey != null) {
                    client.sendFileMessage(chatId, fileKey);
                }
            }
            boolean replied;
            if (config.cardMode()) {
                String card = buildCard(query, markdown, answer.sources(), chatId,
                        !"DOC_WIZARD".equals(answer.route()));
                replied = card != null && client.replyCard(messageId, card);
                if (!replied) {
                    replied = client.replyMessage(messageId, toFeishuText(markdown));   // 卡片失败回退纯文本
                }
            } else {
                replied = client.replyMessage(messageId, toFeishuText(markdown));
            }
            if (!replied) {
                log.warn("[飞书] 回复失败 chat={} messageId={}", chatId, messageId);
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                    answer.route(), query, 0, answer.sources(), 0,
                    answer.rerankDegraded(), answer.graphContextChars(),
                    AuditEvent.truncate(answer.answer(), 500), answer.answer().length(),
                    System.currentTimeMillis() - start, "SUCCESS", null,
                    answer.employeeId()));
        } catch (Exception e) {
            log.error("[飞书] 消息处理失败 chat={}", chatId, e);
            if (messageId != null) {
                client.replyMessage(messageId, "抱歉，处理您的消息时出错了，请稍后再试。");
            }
            auditService.record(new AuditEvent(
                    ctx.traceId(),
                    LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    auditUser != null ? auditUser : ctx.user(), ctx.ip(), "feishu/chat",
                    null, query, 0, List.of(), 0,
                    false, 0, null, 0,
                    System.currentTimeMillis() - start, "FAILED", e.getMessage(), 0));
        }
    }

    /**
     * 构建回答卡片（交互卡片：markdown 渲染正文 + 来源 note + 反馈按钮）。
     * 按钮 value 带动作上下文（重新生成需要原问题与群 ID）。
     * 用 ObjectMapper 序列化——手工拼接遇换行/引号会产出非法 JSON（飞书 400）。
     */
    private String buildCard(String query, String markdown, List<String> sources, String chatId) {
        return buildCard(query, markdown, sources, chatId, true);
    }

    /** showSource=false：文档向导等无来源概念的回复不显示来源注记 */
    private String buildCard(String query, String markdown, List<String> sources, String chatId,
                             boolean showSource) {
        String md = markdown.length() > 6000
                ? markdown.substring(0, 6000) + "\n\n…（内容过长已截断，完整内容请在 Web 端查看）"
                : markdown;
        md = toCardMarkdown(md);   // 飞书卡片 markdown 不支持表格，先展平
        String sourceNote = sources.isEmpty() ? "无来源" : String.join("、", sources);

        java.util.Map<String, Object> regenValue = new java.util.LinkedHashMap<>();
        regenValue.put("action", "regenerate");
        regenValue.put("query", query);
        regenValue.put("chatId", chatId);

        java.util.List<Object> elements = new java.util.ArrayList<>();
        elements.add(java.util.Map.of("tag", "markdown", "content", md));
        elements.add(java.util.Map.of("tag", "hr"));
        if (showSource) {
            elements.add(java.util.Map.of("tag", "note", "elements",
                    java.util.List.of(java.util.Map.of("tag", "plain_text",
                            "content", "📄 来源：" + sourceNote))));
        }
        elements.add(java.util.Map.of("tag", "action", "actions", java.util.List.of(
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "👍 有帮助"),
                        "type", "primary",
                        "value", java.util.Map.of("action", "helpful")),
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "👎 无帮助"),
                        "type", "default",
                        "value", java.util.Map.of("action", "unhelpful")),
                java.util.Map.of("tag", "button",
                        "text", java.util.Map.of("tag", "plain_text", "content", "🔄 重新生成"),
                        "type", "default",
                        "value", regenValue))));

        java.util.Map<String, Object> card = new java.util.LinkedHashMap<>();
        card.put("config", java.util.Map.of("wide_screen_mode", true));
        card.put("header", java.util.Map.of(
                "title", java.util.Map.of("tag", "plain_text", "content", "🤖 数字员工回答"),
                "template", "blue"));
        card.put("elements", elements);
        try {
            return objectMapper.writeValueAsString(card);
        } catch (Exception e) {
            log.error("[飞书] 卡片构建失败（回退纯文本）: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 卡片按钮回调（card.action.trigger）：
     * - helpful/unhelpful → 审计记录 + 感谢回复
     * - regenerate → 重新执行对话 → 原卡片原地更新
     */
    public void handleCardAction(java.util.Map<String, Object> value, String openMessageId,
                                 String openChatId, Object operator) {
        String action = value != null ? String.valueOf(value.getOrDefault("action", "")) : "";
        log.info("[飞书] 卡片动作 {} messageId={} chat={}", action, openMessageId, openChatId);
        switch (action) {
            case "helpful", "unhelpful" -> {
                auditService.record(new AuditEvent(
                        AuditEvent.AuditContext.capture().traceId(),
                        LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        operator != null ? String.valueOf(operator) : "anonymous",
                        "-", "feishu/card_feedback",
                        null, action, 0, List.of(), 0,
                        false, 0, null, 0, 0, "SUCCESS", null, 0));
                client.replyMessage(openMessageId, "helpful".equals(action) ? "感谢反馈 👍" : "收到反馈，我们会持续改进 👎");
            }
            case "regenerate" -> {
                String query = value != null ? String.valueOf(value.getOrDefault("query", "")) : "";
                String chatId = value != null ? String.valueOf(value.getOrDefault("chatId", "")) : "";
                if (query.isBlank() || chatId.isBlank()) {
                    client.replyMessage(openMessageId, "重新生成失败：缺少上下文");
                    return;
                }
                Long agentId = config.defaultAgentId() > 0 ? config.defaultAgentId() : null;
                AgentOrchestrator.AgentAnswer answer = orchestrator.answer(chatId, query, agentId);
                String markdown = StreamTokenFixer.fixFull(answer.answer());
                String card = buildCard(query, markdown, answer.sources(), openChatId);
                if (card == null || !client.updateCard(openMessageId, card)) {
                    client.replyMessage(openMessageId, toFeishuText(markdown));   // 更新失败回退纯文本
                }
            }
            default -> log.warn("[飞书] 未知卡片动作: {}", action);
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
     *  剥加粗/斜体星号，## 标题转【】，<br> 转换行、其余 HTML 标签剥除，
     *  表格转可读文本行（分隔行删除、数据行去首尾管道、中间管道换全角 ｜），保留有序编号与换行 */
    private String toFeishuText(String markdown) {
        String text = markdown
                .replaceAll("(?m)^##\\s+(.+)$", "【$1】")
                .replaceAll("\\*\\*([^*\\n]+)\\*\\*", "$1")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("<[^>]+>", "")
                .replaceAll("(?m)^[\\s>]*>\\s*", "");
        return flattenTables(text);
    }

    /** 卡片 markdown：飞书卡片 markdown 不支持 GFM 表格（会退化成裸管道符行），
     *  仅展平表格为可读文本，其余 markdown 语法（标题/加粗/列表）保留给卡片渲染；
     *  列表项之间插空行——卡片渲染器对列表支持不稳定，相邻列表项可能黏成一段 */
    private String toCardMarkdown(String markdown) {
        String md = flattenTables(markdown);
        // 缩进编号项 → 子弹点：卡片渲染器不支持嵌套有序列表（缩进丢失后 1,2,1,3 观感错乱），
        // 子项转 "- " 后层级语义直观（外层 1. 2. 3.，子项 - 挂在所属项下）
        md = md.replaceAll("(?m)^(\\s+)(\\d+\\.\\s)", "$1- ");
        // 连续列表项（编号/无序）之间补空行，保证每项独立成段
        md = md.replaceAll("(?m)(^(?:\\d+\\.|[-•🔹])\\s+.*)$(?=\\n(?:\\d+\\.|[-•🔹])\\s+)", "$1\n");
        return md;
    }

    /** 表格 → 可读文本：分隔行（|---|---|）删除、数据行去首尾管道、中间管道换全角 ｜。
     *  空单元格继承上一行同列（模型习惯：重复的部门名只写一次，"| | 陈杰 | …" 补 "研发部"）；
     *  连续表格段的首行视为表头，不向下继承（防止表头列名泄漏进数据行）。 */
    private String flattenTables(String text) {
        StringBuilder sb = new StringBuilder();
        String[] prevCells = null;
        boolean prevIsHeader = false;
        for (String line : text.split("\n", -1)) {
            String stripped = line.strip();
            if (stripped.matches("\\|[\\s|:\\-]+\\|")) {
                continue;   // 分隔行（|---|---|）删除
            }
            if (stripped.isEmpty()) {
                sb.append(line).append('\n');
                continue;   // 空行不打断继承链（分隔行删除后留的空行）
            }
            if (stripped.startsWith("|") && stripped.endsWith("|") && stripped.indexOf('|', 1) >= 0) {
                String inner = stripped.substring(1, stripped.length() - 1);
                String[] cells = inner.split("\\|", -1);
                for (int i = 0; i < cells.length; i++) {
                    cells[i] = cells[i].strip();
                }
                if (prevCells == null) {
                    prevIsHeader = true;   // 段首行 = 表头
                } else if (!prevIsHeader) {
                    for (int i = 0; i < cells.length && i < prevCells.length; i++) {
                        if (cells[i].isEmpty() && !prevCells[i].isEmpty()) {
                            cells[i] = prevCells[i];
                        }
                    }
                } else {
                    prevIsHeader = false;
                }
                prevCells = cells;
                sb.append(String.join(" ｜ ", cells));
            } else {
                prevCells = null;   // 非表格行打断继承链
                sb.append(line);
            }
            sb.append('\n');
        }
        return sb.toString().strip();
    }
}
