package com.sora.sora_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 工作流体系配置，映射 application.yml 的 app.workflow 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.workflow")
public class WorkflowProperties {

    /**
     * 额外工作流目录（文件系统路径）。往该目录放 {@code *.yaml} 即可被加载。
     * 留空则只加载 classpath 内置工作流（src/main/resources/workflows/）。
     */
    private String dir;

    /** 工作流体系总开关。 */
    private boolean enabled = true;

    /** 单步超时（秒）：tool/llm 步骤超过则终止该工作流，防止挂起线程泄漏。 */
    private long stepTimeoutSeconds = 120;
}
