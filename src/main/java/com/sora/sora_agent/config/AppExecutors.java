package com.sora.sora_agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用级线程池配置。
 *
 * <p>agent 执行 / 工作流执行等分钟级 LLM 阻塞任务跑在专用池上，避免占用 JVM 公共
 * ForkJoinPool（默认仅 CPU 核数线程）导致并发会话下整站 SSE 饥饿。</p>
 *
 * <p>使用 Java 21 虚拟线程：agent 循环是 I/O 密集（阻塞 LLM HTTP 调用），虚拟线程
 * 让并发会话近乎无上限而不爆平台线程；真正的 LLM 并发由
 * {@code LlmLimitedChatModel} 的信号量控制，避免打爆 DashScope 额度。</p>
 */
@Configuration
public class AppExecutors {

    /**
     * agent 专用执行线程池：虚拟线程（每任务一线程，阻塞时释放平台线程）。
     */
    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        return Executors.newVirtualThreadPerTaskExecutor();
    }
}
