package com.sora.sora_agent.config;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.concurrent.Semaphore;

/**
 * LLM 并发限制装饰器：在真实 ChatModel 外包装一个信号量，限制并发 LLM 调用数。
 *
 * <p>配合虚拟线程使用：虚拟线程提供并发头部空间，本装饰器把真实 DashScope 并发
 * 限制在配置值内（默认 16），避免打爆 API 额度（429 风暴）。</p>
 *
 * <p>ChatModel 接口在 1.1.2 中仅 {@code call(Prompt)} 为抽象方法，其余为默认实现；
 * 这里同时覆写 {@code stream(Prompt)} 以覆盖流式调用路径。</p>
 */
public class LlmLimitedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Semaphore semaphore;

    public LlmLimitedChatModel(ChatModel delegate, Semaphore semaphore) {
        this.delegate = delegate;
        this.semaphore = semaphore;
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        acquire();
        try {
            return delegate.call(prompt);
        } finally {
            semaphore.release();
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        acquire();
        return delegate.stream(prompt).doFinally(signal -> semaphore.release());
    }

    private void acquire() {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("等待 LLM 并发配额被中断", e);
        }
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }
}
