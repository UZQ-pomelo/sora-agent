package com.sora.sora_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 多 Agent（专家 worker）配置，映射 application.yml 的 app.agent 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.agent")
public class WorkerAgentProperties {

    /**
     * 额外专家目录（文件系统路径）。往该目录放 {@code *.yaml} 即可被加载。
     * 留空则只加载 classpath 内置专家（src/main/resources/agents/）。
     */
    private String dir;

    /** 多 Agent 体系总开关。 */
    private boolean enabled = true;

    /** 并行委派的最大并发数（防线程爆炸）。 */
    private int maxConcurrency = 4;

    /** 单次委派总超时（秒），超时返回已完成的部分结果，防止 worker 挂起拖死 supervisor。 */
    private long delegationTimeoutSeconds = 120;
}
