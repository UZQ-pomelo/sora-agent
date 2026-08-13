package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import java.util.ArrayList;
import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationMemory} 单元测试 — 全 Mock，离线可跑。
 */
class ConversationMemoryTest {

    private PgChatMemory chatMemory;
    private ChatModel chatModel;
    private ConversationMemoryProperties props;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        chatMemory = mock(PgChatMemory.class);
        chatModel = mock(ChatModel.class);
        props = new ConversationMemoryProperties();
        props.setNamespace("manus");
        memory = new ConversationMemory(chatMemory, chatModel, props);
    }

    private List<Message> msgs(int rounds) {
        List<Message> list = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            list.add(new UserMessage("用户问题" + i));
            list.add(new AssistantMessage("助手回答" + i));
        }
        return list;
    }

    private void stubSummary(String text) {
        Generation generation = new Generation(new AssistantMessage(text));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
    }

    @Test
    void loadReturnsAllWhenWithinWindow() {
        List<Message> stored = msgs(5);
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(stored);
        assertEquals(stored.size(), memory.load("abc", "tenant1").size());
    }

    @Test
    void loadReturnsEmptyWhenNothingStored() {
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(List.of());
        assertTrue(memory.load("abc", "tenant1").isEmpty());
    }

    @Test
    void loadReturnsAlreadyCompactedDataDirectly() {
        // DB 已裁剪：首条 user + 摘要 system + 窗口消息 → 直接返回，不再摘要
        List<Message> compacted = List.of(
                new UserMessage("首条标题"),
                new SystemMessage(ConversationMemory.SUMMARY_PREFIX + "这是已落库摘要"),
                new UserMessage("最近问题"),
                new AssistantMessage("最近回答"));
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(compacted);

        List<Message> loaded = memory.load("abc", "tenant1");
        assertEquals(4, loaded.size()); // 原样返回
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void loadSummarizesLegacyOverflowInMemory() {
        props.setWindowSize(2); // 保留最近 2 条
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(5));
        stubSummary("这是前情摘要");

        List<Message> loaded = memory.load("abc", "tenant1");
        assertEquals(3, loaded.size()); // 摘要 + 2 条保留
        assertTrue(loaded.get(0) instanceof SystemMessage);
        assertTrue(loaded.get(0).getText().contains("这是前情摘要"));
        assertEquals("用户问题4", loaded.get(1).getText());
        assertEquals("助手回答4", loaded.get(2).getText());
    }

    @Test
    void loadDropsOverflowWhenSummarizeFails() {
        props.setWindowSize(2);
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(5));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));

        List<Message> loaded = memory.load("abc", "tenant1");
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().noneMatch(m -> m instanceof SystemMessage));
    }

    @Test
    void loadSkipsSummarizeWhenDisabled() {
        props.setWindowSize(2);
        props.setSummarizeOverflow(false);
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(5));
        assertEquals(2, memory.load("abc", "tenant1").size());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveFiltersToTextOnlyAndNamespaces() {
        List<Message> messages = List.of(
                new UserMessage("问题"),
                new SystemMessage("系统提示不应落库"),
                new AssistantMessage("回答"));
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(List.of()); // compact 读空，不裁剪
        memory.save("abc", "tenant1", messages);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("tenant1:manus:abc"), captor.capture());
        List<Message> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertTrue(saved.stream().noneMatch(m -> m instanceof SystemMessage));
        assertEquals("问题", saved.get(0).getText());
        assertEquals("回答", saved.get(1).getText());
    }

    @Test
    void saveSkipsWhenNothingToStore() {
        memory.save("abc", "tenant1", List.of());
        verify(chatMemory, never()).add(anyString(), anyList());
        memory.save("abc", "tenant1", null);
        verify(chatMemory, never()).add(anyString(), anyList());
    }

    @Test
    void saveCompactsWhenOverThreshold() {
        props.setWindowSize(2); // 阈值 = 2*2 = 4 条对话
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(5)); // 10 条对话 > 4
        stubSummary("新摘要");

        memory.save("abc", "tenant1", List.of(new UserMessage("新问题"), new AssistantMessage("新回答")));

        // 触发 replace（写侧裁剪）
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).replace(eq("tenant1:manus:abc"), captor.capture());
        List<Message> rebuilt = captor.getValue();
        // 首条 user + 摘要 + 最近 2 条
        assertEquals(4, rebuilt.size());
        assertTrue(rebuilt.get(0) instanceof UserMessage); // 首条标题
        assertTrue(rebuilt.get(1) instanceof SystemMessage); // 摘要
        assertTrue(rebuilt.get(1).getText().startsWith(ConversationMemory.SUMMARY_PREFIX));
    }

    @Test
    void saveDoesNotCompactWhenUnderThreshold() {
        props.setWindowSize(5); // 阈值 10 条
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(2)); // 4 条 < 10
        memory.save("abc", "tenant1", List.of(new UserMessage("x"), new AssistantMessage("y")));
        verify(chatMemory, never()).replace(anyString(), anyList());
    }

    @Test
    void clearUsesNamespace() {
        memory.clear("abc", "tenant1");
        verify(chatMemory).clear("tenant1:manus:abc");
    }
}
