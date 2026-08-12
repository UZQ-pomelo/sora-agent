package com.sora.sora_agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.common.BaseResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * API Key 认证过滤器。
 *
 * <p>对 {@code app.security.protect-patterns} 命中的请求校验 {@code X-API-Key}
 * 请求头；通过认证后按 key 做滑动窗口限流（超限返回 429）。
 * OPTIONS 预检请求直接放行（交由 CORS 处理）。</p>
 *
 * <p>静态资源与接口文档（/index.html、/assets/、/swagger-ui 等）不在默认
 * 保护模式内，保证同源部署时前端 SPA 可正常加载；真实数据接口全部受保护。</p>
 */
@Component
@RequiredArgsConstructor
public class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** API Key 请求头名。 */
    public static final String API_KEY_HEADER = "X-API-Key";

    private final SecurityProperties props;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;
    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true; // 预检请求交给 CORS
        }
        if (!props.isEnabled()) {
            return true;
        }
        // 去掉 context-path 后按 Ant 模式匹配
        String path = request.getRequestURI().substring(request.getContextPath().length());
        List<String> patterns = props.getProtectPatterns();
        if (patterns == null || patterns.isEmpty()) {
            return false; // 空列表 = 保护所有请求（安全默认，避免空配置静默绕过认证）
        }
        return patterns.stream().noneMatch(p -> pathMatcher.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String key = request.getHeader(API_KEY_HEADER);
        if (key == null || !props.getApiKeys().contains(key)) {
            writeError(response, HttpServletResponse.SC_UNAUTHORIZED, 40100, "无效或缺失的 API Key");
            return;
        }
        if (props.getRateLimit().isEnabled()
                && !rateLimiter.tryAcquire(key, props.getRateLimit().getPerKeyPerMinute(), 60_000L)) {
            writeError(response, HttpServletResponse.SC_TOO_MANY_REQUESTS, 42900, "请求过于频繁，已超过限流阈值");
            return;
        }
        filterChain.doFilter(request, response);
    }

    private void writeError(HttpServletResponse response, int httpStatus, int code, String message) throws IOException {
        response.setStatus(httpStatus);
        response.setContentType("application/json;charset=UTF-8");
        response.getOutputStream().write(objectMapper.writeValueAsBytes(BaseResponse.error(code, message)));
        response.getOutputStream().flush();
    }
}
