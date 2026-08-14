package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import com.sora.sora_agent.config.ModelConfig;
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
 *
 * <p>测试用种子比例 1.0（1 字符 = 1 token），消息固定 100 字符，使 token 断言可手工推导。
 * 预算 = contextTokens(2000) − 开销(0) = 2000；高水位 1000、低水位 500。</p>
 */
class ConversationMemoryTest {

    private static final String MODEL = "m";

    private PgChatMemory chatMemory;
    private ChatModel chatModel;
    private ConversationMemoryProperties props;
    private ContextBudgetService budgetService;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() {
        chatMemory = mock(PgChatMemory.class);
        chatModel = mock(ChatModel.class);
        props = new ConversationMemoryProperties();
        props.setNamespace("manus");
        props.setSeedCharPerToken(1.0);
        props.setSeedOverheadTokens(0.0);
        props.setOutputReserveRatio(0.0);
        props.setHighWatermarkRatio(0.5);
        props.setLowWatermarkRatio(0.25);

        ModelConfig.ModelEntry entry = new ModelConfig.ModelEntry();
        entry.setName(MODEL);
        entry.setContextTokens(2000);
        ModelConfig modelConfig = new ModelConfig();
        modelConfig.setAvailable(List.of(entry));

        budgetService = new ContextBudgetService(modelConfig, props);
        memory = new ConversationMemory(chatMemory, chatModel, props, budgetService);
    }

    /** 每轮 = UserMessage(100) + AssistantMessage(100) = 200 token。 */
    private List<Message> msgs(int rounds) {
        List<Message> list = new ArrayList<>();
        for (int i = 0; i < rounds; i++) {
            list.add(new UserMessage("u".repeat(100)));
            list.add(new AssistantMessage("a".repeat(100)));
        }
        return list;
    }

    private void stubSummary(String text) {
        Generation generation = new Generation(new AssistantMessage(text));
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
    }

    @Test
    void loadReturnsAllWhenWithinBudget() {
        List<Message> stored = msgs(5); // 1000 token < 预算 2000
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(stored);
        assertEquals(stored.size(), memory.load("abc", "tenant1", MODEL).size());
    }

    @Test
    void loadReturnsEmptyWhenNothingStored() {
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(List.of());
        assertTrue(memory.load("abc", "tenant1", MODEL).isEmpty());
    }

    @Test
    void loadTrimsByTokenNotCount() {
        // 单条 3000 字符 = 3000 token > 预算 2000，尽管仅 2 条消息也触发裁剪（证明按 token 非条数）
        List<Message> huge = List.of(
                new UserMessage("x".repeat(3000)),
                new AssistantMessage("y"));
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(huge);
        stubSummary("摘要");

        List<Message> loaded = memory.load("abc", "tenant1", MODEL);
        assertTrue(budgetService.estimateTokens(loaded, MODEL) <= 2000,
                "裁剪后应回到预算内，实际 " + budgetService.estimateTokens(loaded, MODEL));
        assertTrue(loaded.stream().noneMatch(m -> m.getText().length() > 100), "超大标题应被丢弃");
    }

    @Test
    void loadTrimsWhenOverBudget() {
        List<Message> stored = msgs(11); // 2200 token > 预算 2000
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(stored);
        stubSummary("摘要");

        List<Message> loaded = memory.load("abc", "tenant1", MODEL);
        assertTrue(budgetService.estimateTokens(loaded, MODEL) < budgetService.estimateTokens(stored, MODEL),
                "超预算应裁剪（token 应减少）");
        assertTrue(loaded.get(0) instanceof UserMessage, "首条 user 作标题锚点");
        assertTrue(loaded.stream().anyMatch(m -> m instanceof SystemMessage), "应含摘要");
    }

    @Test
    void loadDropsOverflowWhenSummarizeFails() {
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(11));
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型不可用"));

        List<Message> loaded = memory.load("abc", "tenant1", MODEL);
        assertTrue(loaded.size() < 22);
        assertTrue(loaded.stream().noneMatch(m -> m instanceof SystemMessage), "摘要失败不应残留摘要");
    }

    @Test
    void loadSkipsSummarizeWhenDisabled() {
        props.setSummarizeOverflow(false);
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(11));
        List<Message> loaded = memory.load("abc", "tenant1", MODEL);
        assertTrue(loaded.size() < 22);
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
        memory.save("abc", "tenant1", MODEL, messages);
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
        memory.save("abc", "tenant1", MODEL, List.of());
        verify(chatMemory, never()).add(anyString(), anyList());
        memory.save("abc", "tenant1", MODEL, null);
        verify(chatMemory, never()).add(anyString(), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void saveCompactsWhenOverHighWatermark() {
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(6)); // 1200 token > 高水位 1000
        stubSummary("新摘要");

        memory.save("abc", "tenant1", MODEL,
                List.of(new UserMessage("新问题"), new AssistantMessage("新回答")));

        ArgumentCaptor<List<Message>> captor = ArgumentCaptor.forClass(List.class);
        verify(chatMemory).replace(eq("tenant1:manus:abc"), captor.capture());
        List<Message> rebuilt = captor.getValue();
        assertTrue(rebuilt.size() < 12, "压缩后应小于原 12 条");
        assertTrue(rebuilt.get(0) instanceof UserMessage, "标题锚点");
        assertTrue(rebuilt.stream().anyMatch(m -> m instanceof SystemMessage
                && m.getText().startsWith(ConversationMemory.SUMMARY_PREFIX)), "应含摘要");
    }

    @Test
    void saveDoesNotCompactWhenUnderHighWatermark() {
        when(chatMemory.get("tenant1:manus:abc")).thenReturn(msgs(2)); // 400 token < 高水位 1000
        memory.save("abc", "tenant1", MODEL,
                List.of(new UserMessage("x"), new AssistantMessage("y")));
        verify(chatMemory, never()).replace(anyString(), anyList());
    }

    @Test
    void clearUsesNamespace() {
        memory.clear("abc", "tenant1");
        verify(chatMemory).clear("tenant1:manus:abc");
    }
}
