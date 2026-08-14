package com.sora.sora_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话记忆配置，映射 application.yml 的 app.memory 节点。
 *
 * <p>控制 agent 会话的持久化命名空间、token 预算与摘要压缩参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.memory")
public class ConversationMemoryProperties {

    /** 会话命名空间前缀（与其它 agent 类型的会话隔离，如 manus:xxx） */
    private String namespace = "manus";

    /** 模型未显式配置 contextTokens 时使用的默认上下文窗口（token 数） */
    private int defaultContextTokens = 64000;

    /** 输出预留比例：模型上下文窗口同时容纳输入+输出，按该比例预留输出空间 */
    private double outputReserveRatio = 0.25;

    /** 压缩高水位：历史估算 token 超过历史预算的该比例时触发压缩 */
    private double highWatermarkRatio = 0.90;

    /** 压缩低水位：压缩后把历史压到历史预算的该比例，留出缓冲避免每轮都摘要 */
    private double lowWatermarkRatio = 0.60;

    /** 字符/token 比例种子（估算器冷启动初值；随 usage 反馈自适应收敛） */
    private double seedCharPerToken = 2.5;

    /** 固定开销种子（system prompt + 工具定义等，随 usage 反馈自适应收敛） */
    private double seedOverheadTokens = 4000;

    /** 超窗是否生成摘要（关闭则超窗部分直接丢弃） */
    private boolean summarizeOverflow = true;

    /** 送入摘要模型的文本长度上限（token 数） */
    private int summarizeInputLimit = 3200;

    /** 会话列表标题截断长度（字符） */
    private int titleMaxLength = 30;
}
