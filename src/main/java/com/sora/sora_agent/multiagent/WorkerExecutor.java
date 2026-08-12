package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.config.WorkerAgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 并行委派执行器：一次把多个任务委派给多个专家 worker，并发执行，全部完成后返回结果。
 *
 * <p>单 worker 失败不影响整批（结果中携带错误信息）；并发数受
 * {@code app.agent.max-concurrency} 限制。默认 {@link WorkerRunner} 由
 * {@link DefaultWorkerRunner} 提供，测试可注入替身。</p>
 */
@Slf4j
@Component
public class WorkerExecutor {

    private final WorkerAgentLoader loader;
    private final WorkerAgentProperties props;
    private final WorkerRunner workerRunner;

    public WorkerExecutor(WorkerAgentLoader loader, WorkerAgentProperties props, WorkerRunner workerRunner) {
        this.loader = loader;
        this.props = props;
        this.workerRunner = workerRunner;
    }

    /**
     * 并行执行一批委派。结果顺序与入参一致。
     * 单次委派总超时由 {@code app.agent.delegation-timeout-seconds} 控制，
     * 超时取消未完成项并返回已完成的部分结果，防止 worker 挂起无限拖死 supervisor。
     */
    public List<DelegationResult> delegateParallel(List<Delegation> delegations) {
        if (delegations == null || delegations.isEmpty()) {
            return List.of();
        }
        int poolSize = Math.min(delegations.size(), Math.max(props.getMaxConcurrency(), 1));
        long timeoutSeconds = Math.max(props.getDelegationTimeoutSeconds(), 1L);
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        try {
            List<CompletableFuture<DelegationResult>> futures = delegations.stream()
                    .map(d -> CompletableFuture.supplyAsync(() -> runOne(d), pool))
                    .toList();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
            List<DelegationResult> results = new ArrayList<>(delegations.size());
            for (int i = 0; i < futures.size(); i++) {
                Delegation d = delegations.get(i);
                long remainingNanos = deadline - System.nanoTime();
                try {
                    if (remainingNanos <= 0) {
                        throw new TimeoutException("委派总超时");
                    }
                    results.add(futures.get(i).get(remainingNanos, TimeUnit.NANOSECONDS));
                } catch (TimeoutException e) {
                    futures.get(i).cancel(true);
                    log.warn("专家[{}]执行超时(>{}s)", d.worker(), timeoutSeconds);
                    results.add(new DelegationResult(d.worker(), d.task(), "执行超时(>" + timeoutSeconds + "s)"));
                } catch (Exception e) {
                    futures.get(i).cancel(true);
                    results.add(new DelegationResult(d.worker(), d.task(), "执行失败: " + e.getMessage()));
                }
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private DelegationResult runOne(Delegation d) {
        try {
            WorkerAgent def = loader.get(d.worker());
            if (def == null) {
                return new DelegationResult(d.worker(), d.task(),
                        "专家不存在: " + d.worker() + "。可用专家: " + String.join(", ", loader.names()));
            }
            String result = workerRunner.run(def, d.task());
            return new DelegationResult(d.worker(), d.task(), result == null ? "" : result);
        } catch (Exception e) {
            log.warn("专家[{}]执行失败: {}", d.worker(), e.getMessage());
            return new DelegationResult(d.worker(), d.task(), "执行失败: " + e.getMessage());
        }
    }

    /** 一次委派：worker 名 + 任务描述。 */
    public record Delegation(String worker, String task) {
    }

    /** 委派结果：worker + 任务 + 结果/错误文本。 */
    public record DelegationResult(String worker, String task, String result) {
    }
}
