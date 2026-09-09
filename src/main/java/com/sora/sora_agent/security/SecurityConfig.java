package com.sora.sora_agent.security;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 安全配置：启动时 fail-fast 校验 + RateLimiter Bean 注册。
 *
 * <p>fail-fast 语义：认证开启（默认）却未配置任何 API Key 时，应用拒绝启动，
 * 避免"忘配 key 裸奔"。显式设置 {@code app.security.enabled=false} 才能绕过。</p>
 */
@Configuration
@RequiredArgsConstructor
public class SecurityConfig {

    private final SecurityProperties props;

    @Bean
    public RateLimiter rateLimiter() {
        return new RateLimiter();
    }

    @PostConstruct
    public void validate() {
        if (props.isEnabled() && (props.getApiKeys() == null || props.getApiKeys().isEmpty())) {
            throw new IllegalStateException(
                    "app.security.enabled=true 但未配置 app.security.api-keys，拒绝启动。"
                            + "请至少配置一个 API Key，或确认部署环境完全可信后显式设置 app.security.enabled=false。");
        }
    }
}
