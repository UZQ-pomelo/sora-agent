package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import com.sora.sora_agent.config.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextBudgetService} 单元测试 — 全离线，验证预算公式与标定收敛。
 */
class ContextBudgetServiceTest {

    private ConversationMemoryProperties props;
    private ModelConfig modelConfig;
    private ContextBudgetService service;

    @BeforeEach
    void setUp() {
        props = new ConversationMemoryProperties();
        props.setDefaultContextTokens(10000);
        props.setOutputReserveRatio(0.25);
        props.setSeedCharPerToken(2.0);
        props.setSeedOverheadTokens(1000);

        ModelConfig.ModelEntry entry = new ModelConfig.ModelEntry();
        entry.setName("m");
        entry.setContextTokens(8000);
        modelConfig = new ModelConfig();
        modelConfig.setAvailable(List.of(entry));

        service = new ContextBudgetService(modelConfig, props);
    }

    @Test
    void historyBudgetSubtractsReserveAndOverhead() {
        // 8000 × (1 − 0.25) − 1000 = 5000
        assertEquals(5000, service.historyBudget("m"));
    }

    @Test
    void contextTokensFallsBackToDefaultWhenUnknownModel() {
        assertEquals(10000, service.contextTokens("unknown"));
        assertEquals(8000, service.contextTokens("m"));
    }

    @Test
    void estimateTokensCeilsCharsDividedByRatio() {
        // 5 字符 ÷ 2.0 = 2.5 → 向上取整 3
        assertEquals(3, service.estimateTokens("12345", "m"));
    }

    @Test
    void estimateTokensSumsMessageText() {
        List<Message> msgs = List.of(
                new UserMessage("question"),   // 8 字符
                new AssistantMessage("answer") // 6 字符
        );
        // 14 字符 ÷ 2.0 = 7
        assertEquals(7, service.estimateTokens(msgs, "m"));
    }

    @Test
    void estimateTokensCountsToolResponseData() {
        // ToolResponseMessage.getText() 恒为空串，但 estimateTokens 必须读到 responseData
        ToolResponseMessage toolMsg = ToolResponseMessage.builder()
                .responses(List.of(new ToolResponseMessage.ToolResponse("id1", "webSearch", "结果文本")))
                .build();
        assertTrue(toolMsg.getText().isEmpty(), "前置：Spring AI 工具结果 getText() 应为空");
        assertEquals(2, service.estimateTokens(toolMsg, "m")); // 4 字符 ÷ 2.0 = 2
        assertEquals(2, service.estimateTokens(List.of(toolMsg), "m"));
    }

    @Test
    void recordUsageConvergesRatioDownwardWhenOverheadHigh() {
        double seedRatio = service.charPerToken("m");
        assertEquals(2.0, seedRatio, 1e-9);

        // 观测：promptTokens 偏高（固定开销大）→ 反推出比例应下调
        service.recordUsage("m", 9000, List.of(new UserMessage("x".repeat(8000))));

        double newRatio = service.charPerToken("m");
        assertTrue(newRatio < seedRatio, "开销偏高时应下调比例，实际 " + newRatio);
    }

    @Test
    void recordUsageIgnoresInvalidInput() {
        double before = service.charPerToken("m");
        service.recordUsage("m", 0, List.of(new UserMessage("x")));
        service.recordUsage("m", 100, List.of());
        assertEquals(before, service.charPerToken("m"), 1e-9);
    }
}
