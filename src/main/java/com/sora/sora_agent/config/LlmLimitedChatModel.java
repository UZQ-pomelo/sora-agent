package com.sora.sora_agent.config;

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sora.sora_agent.chatmemory.ContextBudgetService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.metadata.Usage;
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
 * <p>同时做业务指标埋点（Micrometer）：LLM 调用次数、失败次数、耗时；并把模型上报
 * 的真实 {@code promptTokens} 喂给 {@link ContextBudgetService} 做上下文 token 标定。</p>
 */
@Slf4j
public class LlmLimitedChatModel implements ChatModel {

    private final ChatModel delegate;
    private final Semaphore semaphore;
    private final MeterRegistry meterRegistry;
    private final ContextBudgetService budgetService;
    private final Counter callsCounter;
    private final Counter failuresCounter;
    private final Timer durationTimer;

    public LlmLimitedChatModel(ChatModel delegate, Semaphore semaphore,
                               MeterRegistry meterRegistry, ContextBudgetService budgetService) {
        this.delegate = delegate;
        this.semaphore = semaphore;
        this.meterRegistry = meterRegistry;
        this.budgetService = budgetService;
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
                ChatResponse response = delegate.call(prompt);
                recordUsage(prompt, response);
                return response;
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

    /**
     * usage 标定（仅同步 call 路径；agent 的 think() 走 sync call，stream 暂不采集）。
     * 标定失败绝不影响主流程。
     */
    private void recordUsage(Prompt prompt, ChatResponse response) {
        try {
            Usage usage = response.getMetadata() != null ? response.getMetadata().getUsage() : null;
            if (usage == null || usage.getPromptTokens() == null) {
                return;
            }
            budgetService.recordUsage(modelOf(prompt), usage.getPromptTokens(), prompt.getInstructions());
        } catch (Exception e) {
            log.debug("上下文 token 标定失败（忽略）: {}", e.getMessage());
        }
    }

    private String modelOf(Prompt prompt) {
        ChatOptions opts = prompt.getOptions();
        if (opts instanceof DashScopeChatOptions ds) {
            return ds.getModel();
        }
        return null;
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
