package com.sora.sora_agent.config;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;

/**
 * 把容器中的 ChatModel 统一替换为带并发信号量的装饰器。
 *
 * <p>用 BeanPostProcessor 而非 {@code @Primary}：本项目的 ChatModel 多处按字段名
 * 注入（如 {@code dashscopeChatModel}），@Primary 会被 by-name 注入绕过；
 * 在 postProcessAfterInitialization 返回包装对象可确保所有引用拿到受限版本。</p>
 */
@Slf4j
@Component
public class LlmConcurrencyBeanPostProcessor implements BeanPostProcessor, Ordered {

    private final Semaphore semaphore;
    private final int maxConcurrency;
    private final MeterRegistry meterRegistry;

    public LlmConcurrencyBeanPostProcessor(@Value("${app.executor.llm-max-concurrency:16}") int maxConcurrency,
                                           MeterRegistry meterRegistry) {
        this.maxConcurrency = Math.max(maxConcurrency, 1);
        this.semaphore = new Semaphore(this.maxConcurrency);
        this.meterRegistry = meterRegistry;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ChatModel lm && !(lm instanceof LlmLimitedChatModel)) {
            log.info("为 ChatModel[{}] 注入 LLM 并发限制（最多 {} 并发）", beanName, maxConcurrency);
            return new LlmLimitedChatModel(lm, semaphore, meterRegistry);
        }
        return bean;
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
