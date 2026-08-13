package com.sora.sora_agent.service;

import com.sora.sora_agent.chatmemory.MySQLChatMemory;
import com.sora.sora_agent.config.ConversationMemoryProperties;
import com.sora.sora_agent.mapper.ChatMemoryMessageMapper;
import com.sora.sora_agent.model.dto.ConversationSummary;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 会话管理服务：会话列表 + 会话历史拉取（供前端「对话记录」面板使用）。
 */
@Service
public class ConversationService {

    private final ChatMemoryMessageMapper mapper;
    private final ChatMemory chatMemory;
    private final ConversationMemoryProperties props;

    public ConversationService(ChatMemoryMessageMapper mapper,
                               @Qualifier("mySQLChatMemory") ChatMemory chatMemory,
                               ConversationMemoryProperties props) {
        this.mapper = mapper;
        this.chatMemory = chatMemory;
        this.props = props;
    }

    /**
     * 会话列表（按租户隔离、按最后消息 id 倒序）。
     *
     * @param tenant 租户命名空间（由 API Key 派生）
     */
    public List<ConversationSummary> listConversations(String tenant) {
        String prefix = tenantPrefix(tenant);
        List<ConversationSummary> list = mapper.listConversations(prefix);
        list.forEach(s -> {
            if (s.getConversationId() != null && s.getConversationId().startsWith(prefix)) {
                s.setConversationId(s.getConversationId().substring(prefix.length()));
            }
            if (s.getTitle() != null && s.getTitle().length() > props.getTitleMaxLength()) {
                s.setTitle(s.getTitle().substring(0, props.getTitleMaxLength()) + "…");
            }
        });
        return list;
    }

    /**
     * 拉取某会话的全部历史消息（按租户隔离）。
     */
    public List<Message> getHistory(String conversationId, String tenant) {
        return chatMemory.get(tenantPrefix(tenant) + conversationId);
    }

    private String tenantPrefix(String tenant) {
        String t = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        return t + ":" + props.getNamespace() + ":";
    }
}
