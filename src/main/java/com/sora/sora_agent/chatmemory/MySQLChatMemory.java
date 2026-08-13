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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

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
    private final TransactionTemplate transactionTemplate;

    /** 每会话锁：保证同会话 message_index 分配原子（单实例下足够；跨实例需唯一索引+重试） */
    private final ConcurrentHashMap<String, Object> locks = new ConcurrentHashMap<>();

    public MySQLChatMemory(ChatMemoryMessageMapper mapper, PlatformTransactionManager txManager) {
        this.mapper = mapper;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        // 锁跨事务提交，防止并发同会话读到相同 MAX(message_index)+1
        synchronized (lockFor(conversationId)) {
            transactionTemplate.executeWithoutResult(status -> {
                Long nextIndex = getNextIndex(conversationId);
                for (Message message : messages) {
                    ChatMemoryMessage entity = toEntity(conversationId, message, nextIndex++);
                    mapper.insert(entity);
                }
            });
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

        // 只回读可还原的消息类型，跳过 TOOL/FUNCTION（否则 toMessage 抛异常导致会话记忆崩溃）
        return all.stream()
                .filter(e -> isReadableType(e.getMessageType()))
                .map(this::toMessage)
                .toList();
    }

    private Object lockFor(String conversationId) {
        return locks.computeIfAbsent(conversationId, k -> new Object());
    }

    private boolean isReadableType(String type) {
        if (type == null) {
            return false;
        }
        return switch (type.toLowerCase()) {
            case "system", "user", "assistant" -> true;
            default -> false;
        };
    }

    @Override
    public void clear(String conversationId) {
        mapper.delete(
                new LambdaQueryWrapper<ChatMemoryMessage>()
                        .eq(ChatMemoryMessage::getConversationId, conversationId)
        );
    }

    /**
     * 原子替换某会话的全部消息（供写侧裁剪用：先删后批量插入，重排 message_index）。
     * 与 add/clear 同一把会话锁，保证同会话不并发交错。
     */
    public void replace(String conversationId, List<Message> messages) {
        final List<Message> msgs = (messages == null) ? List.of() : messages;
        synchronized (lockFor(conversationId)) {
            transactionTemplate.executeWithoutResult(status -> {
                mapper.delete(
                        new LambdaQueryWrapper<ChatMemoryMessage>()
                                .eq(ChatMemoryMessage::getConversationId, conversationId));
                for (int i = 0; i < msgs.size(); i++) {
                    mapper.insert(toEntity(conversationId, msgs.get(i), i));
                }
            });
        }
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
