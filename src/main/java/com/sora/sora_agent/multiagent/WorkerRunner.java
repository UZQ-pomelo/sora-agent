package com.sora.sora_agent.multiagent;

/**
 * 专家 worker 的执行器抽象。
 *
 * <p>默认实现 {@link DefaultWorkerRunner} 构建一个 SoraManus 实例（角色提示 + 工具白名单）
 * 同步跑任务；测试可注入替身。</p>
 */
@FunctionalInterface
public interface WorkerRunner {

    /**
     * 以某专家的定义执行一个任务，返回结果文本。
     */
    String run(WorkerAgent def, String task);
}
