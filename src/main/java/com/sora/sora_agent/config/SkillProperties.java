package com.sora.sora_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 技能体系配置，映射 application.yml 的 app.skill 节点。
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.skill")
public class SkillProperties {

    /**
     * 额外技能目录（文件系统路径）。往该目录放 {@code *.yaml} 技能文件即可被加载
     * （文件名即技能名，或文件内 name 字段）。
     * 留空则只加载 classpath 内置技能（src/main/resources/skills/）。
     */
    private String dir;

    /** 技能体系总开关。 */
    private boolean enabled = true;
}
