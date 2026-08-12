package com.sora.sora_agent.service;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import com.sora.sora_agent.mapper.ChatMemoryMessageMapper;
import com.sora.sora_agent.model.dto.ConversationSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationService} 单元测试 — 全 Mock，离线可跑。
 */
class ConversationServiceTest {

    private ChatMemoryMessageMapper mapper;
    private ChatMemory chatMemory;
    private ConversationMemoryProperties props;
    private ConversationService service;

    @BeforeEach
    void setUp() {
        mapper = mock(ChatMemoryMessageMapper.class);
        chatMemory = mock(ChatMemory.class);
        props = new ConversationMemoryProperties();
        props.setNamespace("manus");
        props.setTitleMaxLength(10);
        service = new ConversationService(mapper, chatMemory, props);
    }

    @Test
    void listConversationsStripsNamespaceAndTruncatesTitle() {
        ConversationSummary s = new ConversationSummary();
        s.setConversationId("manus:abc");
        s.setTitle("这是一段非常长的用户消息标题肯定超过十个字");
        s.setMessageCount(5L);
        when(mapper.listConversations("manus:")).thenReturn(List.of(s));

        List<ConversationSummary> list = service.listConversations();
        assertEquals(1, list.size());
        assertEquals("abc", list.get(0).getConversationId());
        assertTrue(list.get(0).getTitle().endsWith("…"));
        assertEquals(11, list.get(0).getTitle().length()); // 10 字符 + 省略号
    }

    @Test
    void listConversationsKeepsShortTitle() {
        ConversationSummary s = new ConversationSummary();
        s.setConversationId("manus:abc");
        s.setTitle("你好");
        when(mapper.listConversations("manus:")).thenReturn(List.of(s));
        assertEquals("你好", service.listConversations().get(0).getTitle());
    }

    @Test
    void getHistoryDelegatesWithNamespace() {
        when(chatMemory.get("manus:abc")).thenReturn(List.of(new UserMessage("hi")));
        assertEquals(1, service.getHistory("abc").size());
        verify(chatMemory).get("manus:abc");
    }
}
