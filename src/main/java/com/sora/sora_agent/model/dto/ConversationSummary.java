package com.sora.sora_agent.model.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话记录摘要（对话列表展示用）。
 */
@Data
public class ConversationSummary {

    /** 会话 id（已去掉命名空间前缀） */
    private String conversationId;

    /** 标题（首条用户消息截断） */
    private String title;

    /** 消息条数 */
    private Long messageCount;

    /** 最后一条消息的 id（AUTO_INCREMENT 全局单调，用作可靠排序依据） */
    private Long lastId;

    /** 最后更新时间（可能为 null，展示用；排序用 lastId） */
    private LocalDateTime lastTime;
}
