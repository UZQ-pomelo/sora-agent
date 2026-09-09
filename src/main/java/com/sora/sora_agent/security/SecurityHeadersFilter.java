package com.sora.sora_agent.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 安全响应头过滤器：为所有响应补充防 MIME 嗅探、防点击劫持、防反向 tabnabbing
 * 等纵深防御头。（SPA 页面若由独立静态托管提供，其 CSP 需在托管层或 index.html 另行配置。）
 */
@Component
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("X-XSS-Protection", "0");
        // 保守 CSP：允许同源脚本/样式内联（Vue 注入 style）与 https 图片；对象/框架禁
        response.setHeader("Content-Security-Policy",
                "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; "
                + "img-src 'self' data: https:; connect-src 'self'; font-src 'self' data:; "
                + "object-src 'none'; base-uri 'self'; frame-ancestors 'none'");
        filterChain.doFilter(request, response);
    }
}
