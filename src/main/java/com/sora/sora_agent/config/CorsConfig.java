package com.sora.sora_agent.config;

import com.sora.sora_agent.security.SecurityProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 全局跨域配置 — 来源收紧为可配置列表（app.security.cors.allowed-origins）。
 *
 * <p>认证走 X-API-Key 请求头（非 Cookie），因此不再需要 allowCredentials(true)，
 * 同时用精确来源列表取代原来的通配符，缩小跨域暴露面。</p>
 */
@Configuration
@RequiredArgsConstructor
public class CorsConfig implements WebMvcConfigurer {

    private final SecurityProperties securityProperties;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOrigins(securityProperties.getCors().getAllowedOrigins().toArray(new String[0]))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*");
    }
}
