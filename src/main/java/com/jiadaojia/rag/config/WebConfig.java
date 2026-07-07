package com.jiadaojia.rag.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.CorsFilter;

import java.util.Arrays;

/**
 * CORS 配置（P2-18）
 * 用 CorsFilter Bean（order=0 最早执行）确保对所有请求（含 SSE）可靠生效。
 *
 * 用 allowedOriginPatterns 而非 allowedOrigins：
 * - allowedOriginPatterns("*") 兼容 allowCredentials=true（allowedOrigins("*") 不兼容）
 * - dev 默认 * 放行所有 origin，生产改为具体域名
 */
@Configuration
public class WebConfig {

    /**
     * 允许的 origin 模式。
     * dev 默认 *（放行所有）；生产在 application-prod.yml 设为具体域名：
     *   rag.cors.allowed-origins: https://admin.example.com
     */
    @Value("${rag.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Bean
    public FilterRegistrationBean<CorsFilter> corsFilterRegistration() {
        CorsConfiguration config = new CorsConfiguration();
        // allowedOriginPatterns 支持 * 通配且兼容 allowCredentials=true
        config.setAllowedOriginPatterns(Arrays.asList(allowedOrigins));
        config.setAllowedMethods(Arrays.asList("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(Arrays.asList("*"));
        config.setExposedHeaders(Arrays.asList("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // 对所有路径生效
        source.registerCorsConfiguration("/**", config);

        FilterRegistrationBean<CorsFilter> bean = new FilterRegistrationBean<>(new CorsFilter(source));
        // 最高优先级，确保在其它过滤器前执行
        bean.setOrder(0);
        return bean;
    }
}
