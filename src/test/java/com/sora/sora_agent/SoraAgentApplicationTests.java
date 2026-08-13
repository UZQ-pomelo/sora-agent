package com.sora.sora_agent;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 上下文冒烟测试（唯一集成测试）。
 *
 * <p>依赖真实 PostgreSQL/pgvector/DashScope API key，且因
 * {@code app.security.api-keys} fail-fast 需要 {@code application-local.yml} 存在。
 * 默认被 {@code @Tag("integration")} 标记，配合 surefire 排除组在常规构建中跳过，
 * 仅在本地配齐环境后按标签手动运行。</p>
 */
@SpringBootTest
@Tag("integration")
class SoraAgentApplicationTests {

    @Test
    void contextLoads() {
    }

}
