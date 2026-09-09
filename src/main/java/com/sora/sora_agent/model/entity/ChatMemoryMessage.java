package com.sora.sora_agent.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * PostgreSQL 对话存储实体类
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_memory_message")
public class ChatMemoryMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 对话id
     */
    private String conversationId;

    /**
     * 对话序号
     */
    private Long messageIndex;

    /**
     * 对话类型
     */
    private String messageType;

    /**
     * 具体文本
     */
    private String messageText;

    /**
     * 创建时间
     */
    private LocalDateTime createTime;

}
