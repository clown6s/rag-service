package com.zzp.rag.feishu;

import com.lark.oapi.event.EventDispatcher;
import com.lark.oapi.service.im.ImService;
import com.lark.oapi.service.im.v1.model.EventMessage;
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1;
import com.lark.oapi.ws.Client;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 飞书长连接客户端（内网首选模式）——基于官方 Java SDK（oapi-sdk 2.8.5）。
 * <p>
 * SDK 内部封装：WS 地址获取（/callback/ws/endpoint）、protobuf 帧编解码、
 * ping 心跳、ack 回执、断线重连——自研不可靠的部分全部交给官方实现。
 * 应用主动出站连接飞书服务器，不需要公网回调地址（内网数据面友好）。
 * <p>
 * 事件路由：EventDispatcher 注册 im.message.receive_v1 → {@link FeishuChatBridge}。
 * 软依赖：未启用/配置不完整不启动，平台默认不依赖任何 IM。
 */
@Slf4j
@Component
public class FeishuWsClient {

    private final FeishuConfig config;
    private final FeishuChatBridge bridge;

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile Client client;

    public FeishuWsClient(FeishuConfig config, FeishuChatBridge bridge) {
        this.config = config;
        this.bridge = bridge;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!config.ready() || !config.longConnection() || !started.compareAndSet(false, true)) {
            return;
        }
        try {
            EventDispatcher dispatcher = EventDispatcher.newBuilder("", "")
                    .onP2CardActionTrigger(new com.lark.oapi.event.cardcallback.P2CardActionTriggerHandler() {
                        @Override
                        public com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse handle(
                                com.lark.oapi.event.cardcallback.model.P2CardActionTrigger event) {
                            try {
                                var data = event.getEvent();
                                // operator.open_id 与消息路径的 open_id 一致（向导状态 key 依赖此一致性）；缺失回退 tenant_key
                                String opId = null;
                                if (data.getOperator() != null) {
                                    opId = data.getOperator().getOpenId() != null
                                            && !data.getOperator().getOpenId().isBlank()
                                            ? data.getOperator().getOpenId()
                                            : data.getOperator().getTenantKey();
                                }
                                bridge.handleCardAction(
                                        data.getAction().getValue(),
                                        data.getAction().getFormValue(),
                                        data.getContext().getOpenMessageId(),
                                        data.getContext().getOpenChatId(),
                                        opId);
                            } catch (Exception e) {
                                log.warn("[飞书] 卡片回调处理异常: {}", e.getMessage());
                            }
                            var resp = new com.lark.oapi.event.cardcallback.model.P2CardActionTriggerResponse();
                            var toast = new com.lark.oapi.event.cardcallback.model.CallBackToast();
                            toast.setType("info");
                            toast.setContent("已收到");
                            resp.setToast(toast);
                            return resp;
                        }
                    })
                    .onP2MessageReceiveV1(new ImService.P2MessageReceiveV1Handler() {
                        @Override
                        public void handle(P2MessageReceiveV1 event) {
                            EventMessage message = event.getEvent().getMessage();
                            String openId = "";
                            try {
                                openId = event.getEvent().getSender().getSenderId().getOpenId();
                            } catch (Exception ignored) {
                                // sender 缺失（罕见）→ 审计回退 anonymous
                            }
                            bridge.handleMessage(
                                    message.getChatId(),
                                    message.getMessageId(),
                                    message.getMessageType(),
                                    message.getContent(),
                                    openId);
                        }
                    })
                    .build();
            // SDK domain 参数要完整 URL（含协议）；SaaS/私有化只换 baseUrl
            client = new Client.Builder(config.appId(), config.appSecret())
                    .domain(config.baseUrl())
                    .eventHandler(dispatcher)
                    .autoReconnect(true)
                    .build();
            client.start();
            client.awaitReady(30_000);   // 等待握手完成（失败抛异常，不阻塞启动）
            log.info("[飞书] 长连接就绪 domain={}", config.baseUrl());
        } catch (Exception e) {
            log.warn("[飞书] 长连接启动失败（软依赖，不阻断平台）: {}", e.getMessage());
            started.set(false);   // 允许后续手动重试/配置修正后重启
        }
    }

    @PreDestroy
    public void stop() {
        Client c = client;
        if (c != null) {
            try {
                c.close();
            } catch (Exception ignored) {
            }
        }
        log.info("[飞书] 长连接已停止");
    }
}
