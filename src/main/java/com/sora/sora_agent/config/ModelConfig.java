package com.sora.sora_agent.config;

import jakarta.annotation.PostConstruct;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 可用模型配置，映射 application.yml 中的 app.models 节点。
 *
 * <p>YAML 结构示例：
 * <pre>
 * app:
 *   models:
 *     available:
 *       - name: deepseek-v4-flash
 *         display: "DeepSeek V4 Flash"
 *       - name: qwen-turbo
 *         display: "Qwen Turbo"
 *       - name: qwen-plus
 *         display: "Qwen Plus"
 *     default-model: deepseek-v4-flash
 * </pre>
 * </p>
 *
 * <p>列表顺序即 fallback 顺序：排名靠前的优先尝试。</p>
 */
@Slf4j
@Data
@Component
@ConfigurationProperties(prefix = "app.models")
public class ModelConfig {

    /** 可用模型列表（有序 — 即 fallback 顺序） */
    private List<ModelEntry> available = new ArrayList<>();

    /** 默认模型名 */
    private String defaultModel;

    @PostConstruct
    public void logConfig() {
        log.info("已加载模型配置: available={}, defaultModel={}",
                available.stream().map(ModelEntry::getName).toList(),
                defaultModel);
    }

    /**
     * 单个模型条目。
     */
    @Data
    public static class ModelEntry {
        /** 模型标识名（传给 DashScope API 的值） */
        private String name;
        /** 前端展示名 */
        private String display;
        /** 模型上下文窗口 token 数（可选；缺省回落 {@code app.memory.default-context-tokens}） */
        private Integer contextTokens;
    }
}
