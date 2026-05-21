package com.sora.sora_agent.chatmemory;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.sora.sora_agent.mapper.ChatMemoryMessageMapper;
import com.sora.sora_agent.model.entity.ChatMemoryMessage;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 基于 MySQL（MyBatis-Plus）的 ChatMemory 持久化实现。
 * <p>
 * Spring AI 1.1.x 中 ChatMemory 接口的 {@code get} 方法已移除 {@code lastN} 参数，
 * 窗口控制由上层的 {@code MessageWindowChatMemory} 统一管理。
 * </p>
 */
@Component
public class MySQLChatMemory implements ChatMemory {

    private final ChatMemoryMessageMapper mapper;

    public MySQLChatMemory(ChatMemoryMessageMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    @Transactional
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        Long nextIndex = getNextIndex(conversationId);
        for (Message message : messages) {
            ChatMemoryMessage entity = toEntity(conversationId, message, nextIndex++);
            mapper.insert(entity);
        }
    }

    @Override
    public List<Message> get(String conversationId) {
        List<ChatMemoryMessage> all = mapper.selectList(
                new LambdaQueryWrapper<ChatMemoryMessage>()
                        .eq(ChatMemoryMessage::getConversationId, conversationId)
                        .orderByAsc(ChatMemoryMessage::getMessageIndex)
        );

        if (all == null || all.isEmpty()) {
            return List.of();
        }

        return all.stream()
                .map(this::toMessage)
                .toList();
    }

    @Override
    public void clear(String conversationId) {
        mapper.delete(
                new LambdaQueryWrapper<ChatMemoryMessage>()
                        .eq(ChatMemoryMessage::getConversationId, conversationId)
        );
    }

    /**
     * 计算下一条消息的序号（自增）。
     */
    private Long getNextIndex(String conversationId) {
        LambdaQueryWrapper<ChatMemoryMessage> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(ChatMemoryMessage::getConversationId, conversationId)
                .orderByDesc(ChatMemoryMessage::getMessageIndex)
                .last("LIMIT 1");
        ChatMemoryMessage lastMessage = mapper.selectOne(queryWrapper);
        return lastMessage == null ? 0L : lastMessage.getMessageIndex() + 1;
    }

    /**
     * Spring AI Message → 数据库实体。
     */
    private ChatMemoryMessage toEntity(String conservationId, Message message, long index) {
        ChatMemoryMessage chatMemoryMessage = new ChatMemoryMessage();
        chatMemoryMessage.setConversationId(conservationId);
        chatMemoryMessage.setMessageIndex(index);
        chatMemoryMessage.setMessageType(message.getMessageType().getValue());
        chatMemoryMessage.setMessageText(message.getText());
        return chatMemoryMessage;
    }

    /**
     * 数据库实体 → Spring AI Message。
     */
    private Message toMessage(ChatMemoryMessage chatMemoryMessage) {
        MessageType messageType = MessageType.valueOf(chatMemoryMessage.getMessageType().toUpperCase());
        return switch (messageType) {
            case SYSTEM -> new SystemMessage(chatMemoryMessage.getMessageText());
            case USER -> new UserMessage(chatMemoryMessage.getMessageText());
            case ASSISTANT -> new AssistantMessage(chatMemoryMessage.getMessageText());
            default -> throw new IllegalArgumentException("未知的消息类型: " + chatMemoryMessage.getMessageType());
        };
    }
}
