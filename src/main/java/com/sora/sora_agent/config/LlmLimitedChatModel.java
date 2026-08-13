package com.sora.sora_agent.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
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
 * <p>同时做业务指标埋点：LLM 调用次数、失败次数、耗时（Micrometer）。</p>
 */
public class LlmLimitedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Semaphore semaphore;
    private final MeterRegistry meterRegistry;
    private final Counter callsCounter;
    private final Counter failuresCounter;
    private final Timer durationTimer;

    public LlmLimitedChatModel(ChatModel delegate, Semaphore semaphore, MeterRegistry meterRegistry) {
        this.delegate = delegate;
        this.semaphore = semaphore;
        this.meterRegistry = meterRegistry;
        this.callsCounter = Counter.builder("llm.calls").description("LLM 调用总次数").register(meterRegistry);
        this.failuresCounter = Counter.builder("llm.failures").description("LLM 调用失败次数").register(meterRegistry);
        this.durationTimer = Timer.builder("llm.duration").description("LLM 调用耗时").register(meterRegistry);
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        acquire();
        return durationTimer.record(() -> {
            try {
                callsCounter.increment();
                return delegate.call(prompt);
            } catch (Exception e) {
                failuresCounter.increment();
                throw e;
            } finally {
                semaphore.release();
            }
        });
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        acquire();
        return delegate.stream(prompt)
                .doOnSubscribe(s -> callsCounter.increment())
                .doOnError(e -> failuresCounter.increment())
                .doFinally(signal -> semaphore.release());
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
