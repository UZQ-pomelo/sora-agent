package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import com.sora.sora_agent.config.ModelConfig;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * 上下文 token 预算服务：按模型估算「历史可用 token 预算」与「消息列表 token 数」。
 *
 * <p>单一事实来源，供 {@link ConversationMemory} 的加载/压缩裁剪使用。核心公式：</p>
 * <pre>
 *   历史预算(model) = contextTokens(model) × (1 − outputReserveRatio) − 固定开销(model)
 *   估算(tokens)    = 文本字符数 ÷ charPerToken(model)
 * </pre>
 *
 * <p>标定（自适应）：每次 LLM 调用后通过 {@link #recordUsage} 喂入模型上报的真实
 * {@code promptTokens} 与消息文本字符数，反推「字符/token 比例」与「固定开销」
 * （system prompt + 工具定义，不在消息文本里却计入 promptTokens），滚动收敛。
 * 状态仅存内存：重启后用种子值重收敛（几轮内到位）。</p>
 */
@Component
public class ContextBudgetService {

    /** 字符/token 比例收敛区间（中文 ~1、英文 ~4，混排取中段） */
    private static final double MIN_CHAR_PER_TOKEN = 1.0;
    private static final double MAX_CHAR_PER_TOKEN = 6.0;
    /** 固定开销收敛区间（system + 工具定义，防御异常值） */
    private static final double MIN_OVERHEAD = 500;
    private static final double MAX_OVERHEAD = 30000;
    /** 历史预算下限：即使窗口极小/开销极大，也至少保留一小段上下文 */
    private static final int MIN_HISTORY_BUDGET = 512;
    /** 标定收敛权重：新观测占比（低权重 = 稳定、抗抖动） */
    private static final double BLEND_WEIGHT = 0.2;

    private final ModelConfig modelConfig;
    private final ConversationMemoryProperties props;
    private final ConcurrentMap<String, ModelBudgetState> states = new ConcurrentHashMap<>();

    public ContextBudgetService(ModelConfig modelConfig, ConversationMemoryProperties props) {
        this.modelConfig = modelConfig;
        this.props = props;
    }

    /** 某模型可分配给「历史消息」的 token 预算。 */
    public int historyBudget(String model) {
        double window = contextTokens(model) * (1.0 - props.getOutputReserveRatio());
        double overhead = overheadTokens(model);
        return Math.max((int) Math.floor(window - overhead), MIN_HISTORY_BUDGET);
    }

    /** 估算消息列表的 token 数（按文本字符数，含工具结果；忽略元数据/工具定义）。 */
    public int estimateTokens(List<Message> messages, String model) {
        return estimateTokens(charCount(messages), model);
    }

    /** 估算单条消息的 token 数（含工具结果）。 */
    public int estimateTokens(Message message, String model) {
        if (message == null) {
            return 0;
        }
        return estimateTokens(messageText(message), model);
    }

    /** 估算一段文本的 token 数。 */
    public int estimateTokens(String text, String model) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return estimateTokens((long) text.length(), model);
    }

    /** 模型上下文窗口 token 数（ModelEntry 显式值，缺省回落默认）。 */
    public int contextTokens(String model) {
        ModelConfig.ModelEntry entry = findEntry(model);
        if (entry != null && entry.getContextTokens() != null && entry.getContextTokens() > 0) {
            return entry.getContextTokens();
        }
        return props.getDefaultContextTokens();
    }

    /** 当前字符/token 比例（供会话列表估算与测试断言）。 */
    public double charPerToken(String model) {
        return state(model).charPerToken;
    }

    /**
     * usage 反馈：用模型真实上报的 promptTokens 反推标定。
     *
     * @param model        模型名（null/空白走默认桶）
     * @param promptTokens 模型上报的输入 token 总数（含 system+工具定义+消息文本）
     * @param messages     送入模型的消息列表（据此计算含工具结果的文本字符数）
     */
    public void recordUsage(String model, int promptTokens, List<Message> messages) {
        if (promptTokens <= 0) {
            return;
        }
        long textChars = charCount(messages);
        if (textChars <= 0) {
            return;
        }
        ModelBudgetState s = state(model);
        // 用当前比例估算文本 token，反推开销（system + 工具定义）
        double textTokensEst = textChars / s.charPerToken;
        double overheadObs = promptTokens - textTokensEst;
        s.overheadTokens = clamp(blend(s.overheadTokens, overheadObs), MIN_OVERHEAD, MAX_OVERHEAD);
        // 去掉开销后，用真实文本 token 数反推更准的比例
        double textTokensTrue = Math.max(promptTokens - s.overheadTokens, 1);
        double ratioObs = textChars / textTokensTrue;
        s.charPerToken = clamp(blend(s.charPerToken, ratioObs), MIN_CHAR_PER_TOKEN, MAX_CHAR_PER_TOKEN);
    }

    // ---- 内部 ----

    private int estimateTokens(long chars, String model) {
        if (chars <= 0) {
            return 0;
        }
        return (int) Math.ceil(chars / state(model).charPerToken);
    }

    /** 消息列表的总文本字符数（含工具结果）。 */
    private static long charCount(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        return messages.stream()
                .mapToLong(m -> {
                    String t = messageText(m);
                    return t == null ? 0 : t.length();
                })
                .sum();
    }

    /**
     * 消息的文本内容。关键：{@link ToolResponseMessage#getText()} 恒为空串（Spring AI 把
     * 工具结果放在 {@code getResponses()} 里），必须显式读取 responseData，否则工具结果
     * 会被统计成 0 token，导致循环内裁剪失效。
     */
    private static String messageText(Message m) {
        if (m instanceof ToolResponseMessage trm) {
            return trm.getResponses().stream()
                    .map(ToolResponseMessage.ToolResponse::responseData)
                    .filter(Objects::nonNull)
                    .collect(Collectors.joining("\n"));
        }
        return m.getText();
    }

    private double overheadTokens(String model) {
        return state(model).overheadTokens;
    }

    private ModelBudgetState state(String model) {
        String key = (model == null || model.isBlank()) ? "" : model;
        return states.computeIfAbsent(key, k -> new ModelBudgetState(
                props.getSeedCharPerToken(), props.getSeedOverheadTokens()));
    }

    private ModelConfig.ModelEntry findEntry(String model) {
        if (model == null || model.isBlank() || modelConfig.getAvailable() == null) {
            return null;
        }
        return modelConfig.getAvailable().stream()
                .filter(e -> model.equals(e.getName()))
                .findFirst()
                .orElse(null);
    }

    private static double blend(double oldValue, double observed) {
        return oldValue + (observed - oldValue) * BLEND_WEIGHT;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 每个模型的运行时标定状态（内存态，重启重收敛）。 */
    private static final class ModelBudgetState {
        volatile double charPerToken;
        volatile double overheadTokens;

        ModelBudgetState(double charPerToken, double overheadTokens) {
            this.charPerToken = charPerToken;
            this.overheadTokens = overheadTokens;
        }
    }
}
