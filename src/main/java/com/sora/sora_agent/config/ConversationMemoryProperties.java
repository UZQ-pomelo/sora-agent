package com.sora.sora_agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 会话记忆配置，映射 application.yml 的 app.memory 节点。
 *
 * <p>控制 agent 会话的持久化命名空间、上下文窗口与摘要压缩参数。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.memory")
public class ConversationMemoryProperties {

    /** 会话命名空间前缀（与其它 agent 类型的会话隔离，如 manus:xxx） */
    private String namespace = "manus";

    /** 上下文保留最近消息条数；超过则把更早部分压缩为摘要 */
    private int windowSize = 20;

    /** 超窗是否生成摘要（关闭则超窗部分直接丢弃） */
    private boolean summarizeOverflow = true;

    /** 送入摘要模型的文本长度上限（字符） */
    private int summarizeInputLimit = 8000;

    /** 会话列表标题截断长度（字符） */
    private int titleMaxLength = 30;
}
