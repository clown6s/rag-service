package com.zzp.rag.feishu;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 飞书接入配置（渠道适配器：SaaS 与私有化同构，只换 baseUrl）。
 * <p>
 * rag.feishu.enabled=false 时所有飞书组件停用（软依赖，平台默认不依赖任何 IM）。
 */
@Slf4j
@Component
public class FeishuConfig {

    @Value("${rag.feishu.enabled:false}")
    private boolean enabled;

    /** 开放平台域名（SaaS: https://open.feishu.cn；私有化: 自有域名），尾斜杠统一剥掉 */
    @Value("${rag.feishu.base-url:https://open.feishu.cn}")
    private String baseUrl;

    @Value("${rag.feishu.app-id:}")
    private String appId;

    @Value("${rag.feishu.app-secret:}")
    private String appSecret;

    /** 事件订阅 verification token（回调模式验签；长连接模式不用） */
    @Value("${rag.feishu.verification-token:}")
    private String verificationToken;

    /** 默认绑定数字员工 id（0 = 平台默认员工） */
    @Value("${rag.feishu.default-agent-id:0}")
    private long defaultAgentId;

    /** 长连接模式（推荐内网：出站 WS 收事件，无需公网回调）；false = HTTP 回调模式 */
    @Value("${rag.feishu.long-connection:true}")
    private boolean longConnection;

    public boolean enabled() {
        return enabled;
    }

    public String baseUrl() {
        String b = baseUrl == null ? "" : baseUrl.strip();
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    public String appId() {
        return appId;
    }

    public String appSecret() {
        return appSecret;
    }

    public String verificationToken() {
        return verificationToken;
    }

    public long defaultAgentId() {
        return defaultAgentId;
    }

    public boolean longConnection() {
        return longConnection;
    }

    /** 基本配置齐备（enabled + 域名 + 凭据） */
    public boolean ready() {
        boolean ready = enabled && !baseUrl().isBlank()
                && appId != null && !appId.isBlank()
                && appSecret != null && !appSecret.isBlank();
        if (!ready) {
            log.warn("[飞书] 配置不完整，接入停用: enabled={} baseUrl={} appId={} appSecret={}",
                    enabled, baseUrl(), appId, appSecret != null && !appSecret.isBlank() ? "***" : "");
        }
        return ready;
    }
}
