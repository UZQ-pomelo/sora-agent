package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link ConversationMemory} 单元测试 — 全 Mock，离线可跑。
 */
class ConversationMemoryTest {

    private ChatMemory chatMemory;
    private ChatModel chatModel;
    private ConversationMemoryProperties props;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
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
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text));
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        when(chatModel.call(any(Prompt.class))).thenReturn(response);
    }

    @Test
    void loadReturnsAllWhenWithinWindow() {
        List<Message> stored = msgs(5);
        when(chatMemory.get("manus:abc")).thenReturn(stored);
        assertEquals(stored.size(), memory.load("abc").size());
    }

    @Test
    void loadReturnsEmptyWhenNothingStored() {
        when(chatMemory.get("manus:abc")).thenReturn(List.of());
        assertTrue(memory.load("abc").isEmpty());
    }

    @Test
    void loadSummarizesOverflowAndInjectsSystemMessage() {
        props.setWindowSize(2); // 保留最近 2 条（1 轮 user+assistant）
        List<Message> stored = msgs(5); // 共 10 条
        when(chatMemory.get("manus:abc")).thenReturn(stored);
        stubSummary("这是前情摘要");

        List<Message> loaded = memory.load("abc");
        assertEquals(3, loaded.size()); // 摘要 + 2 条保留
        assertTrue(loaded.get(0) instanceof SystemMessage);
        assertTrue(loaded.get(0).getText().contains("这是前情摘要"));
        // 保留的是最后两条（第 5 轮）
        assertEquals("用户问题4", loaded.get(1).getText());
        assertEquals("助手回答4", loaded.get(2).getText());
        verify(chatModel).call(any(Prompt.class));
    }

    @Test
    void loadDropsOverflowWhenSummarizeFails() {
        props.setWindowSize(2);
        when(chatMemory.get("manus:abc")).thenReturn(msgs(5));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));

        List<Message> loaded = memory.load("abc");
        // 摘要失败 → 安全降级为丢弃超窗部分，只保留窗口内
        assertEquals(2, loaded.size());
        assertTrue(loaded.stream().noneMatch(m -> m instanceof SystemMessage));
    }

    @Test
    void loadSkipsSummarizeWhenDisabled() {
        props.setWindowSize(2);
        props.setSummarizeOverflow(false);
        when(chatMemory.get("manus:abc")).thenReturn(msgs(5));
        assertEquals(2, memory.load("abc").size());
        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void loadReusesSummaryFromCache() {
        props.setWindowSize(2);
        when(chatMemory.get("manus:abc")).thenReturn(msgs(5));
        stubSummary("缓存摘要");

        memory.load("abc");  // 第一次：调用摘要模型并缓存
        memory.load("abc");  // 第二次：命中缓存，不再调用

        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void clearInvalidatesSummaryCache() {
        props.setWindowSize(2);
        when(chatMemory.get("manus:abc")).thenReturn(msgs(5));
        stubSummary("摘要A");

        memory.load("abc");
        memory.clear("abc");  // 清会话同时清缓存
        memory.load("abc");   // 重新生成摘要

        verify(chatModel, times(2)).call(any(Prompt.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveFiltersToTextOnlyAndNamespaces() {
        List<Message> messages = List.of(
                new UserMessage("问题"),
                new SystemMessage("系统提示不应落库"),
                new AssistantMessage("回答"));
        memory.save("abc", messages);
        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).add(eq("manus:abc"), captor.capture());
        List<Message> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertTrue(saved.stream().noneMatch(m -> m instanceof SystemMessage));
        assertEquals("问题", saved.get(0).getText());
        assertEquals("回答", saved.get(1).getText());
    }

    @Test
    void saveSkipsWhenNothingToStore() {
        memory.save("abc", List.of());
        verify(chatMemory, never()).add(any(), any());
        memory.save("abc", null);
        verify(chatMemory, never()).add(any(), any());
    }

    @Test
    void clearUsesNamespace() {
        memory.clear("abc");
        verify(chatMemory).clear("manus:abc");
    }
}
