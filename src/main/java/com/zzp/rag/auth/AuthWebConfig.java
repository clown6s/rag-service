package com.zzp.rag.auth;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 鉴权拦截注册：管理后台 /api/rag/admin/** + 对话端点 /api/rag/agent/**
 * （对话也需登录——审计身份闭环；飞书桥 /api/rag/bridge/** 自带验签、MCP 内部端点不拦截）。
 */
@Configuration
public class AuthWebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    public AuthWebConfig(AuthInterceptor authInterceptor) {
        this.authInterceptor = authInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/rag/admin/**", "/api/rag/agent/**");
    }
}
