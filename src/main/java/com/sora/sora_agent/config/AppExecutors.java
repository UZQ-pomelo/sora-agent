package com.sora.sora_agent.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 应用级线程池配置。
 *
 * <p>agent 执行 / 工作流执行等分钟级 LLM 阻塞任务跑在专用池上，
 * 避免占用 JVM 公共 ForkJoinPool（默认仅 CPU 核数线程）导致并发会话下整站 SSE 饥饿。</p>
 */
@Configuration
public class AppExecutors {

    /**
     * agent 专用执行线程池（有界固定池，任务排队等待，避免无界线程爆炸）。
     */
    @Bean(name = "agentExecutor", destroyMethod = "shutdown")
    public ExecutorService agentExecutor() {
        int threads = Math.max(4, Runtime.getRuntime().availableProcessors());
        return Executors.newFixedThreadPool(threads);
    }
}
