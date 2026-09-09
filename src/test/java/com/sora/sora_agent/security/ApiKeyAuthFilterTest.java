package com.sora.sora_agent.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link ApiKeyAuthFilter} 测试 — 使用 MockMvc standalone（不启动 Spring 上下文，离线可跑）。
 */
class ApiKeyAuthFilterTest {

    @RestController
    static class DummyController {
        @GetMapping("/ai/ping")
        public String ping() {
            return "pong";
        }
    }

    private SecurityProperties props;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        props = new SecurityProperties();
        props.setApiKeys(List.of("key-1", "key-2"));
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter(props, new RateLimiter(), new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new DummyController())
                .addFilters(filter)
                .build();
    }

    @Test
    void missingKeyReturns401() throws Exception {
        mockMvc.perform(get("/ai/ping")).andExpect(status().isUnauthorized());
    }

    @Test
    void wrongKeyReturns401() throws Exception {
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void validKeyAllowed() throws Exception {
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "key-1"))
                .andExpect(status().isOk());
    }

    @Test
    void secondKeyAllowed() throws Exception {
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "key-2"))
                .andExpect(status().isOk());
    }

    @Test
    void rateLimitExceededReturns429() throws Exception {
        props.getRateLimit().setPerKeyPerMinute(2);
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "key-1")).andExpect(status().isOk());
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "key-1")).andExpect(status().isOk());
        mockMvc.perform(get("/ai/ping").header("X-API-Key", "key-1"))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void optionsPreflightAllowedWithoutKey() throws Exception {
        mockMvc.perform(options("/ai/ping")).andExpect(status().isOk());
    }

    @Test
    void nonProtectedPathNotRejectedByAuth() throws Exception {
        // 静态资源路径不在保护模式内：无 key 不应 401（此处 404 是未被 standalone 映射，而非认证拦截）
        mockMvc.perform(get("/index.html")).andExpect(status().isNotFound());
    }

    @Test
    void disabledAuthSkipsCheck() throws Exception {
        props.setEnabled(false);
        mockMvc.perform(get("/ai/ping")).andExpect(status().isOk());
    }
}
