package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 会话记忆服务：无状态 agent + 外部记忆（写侧裁剪 + 摘要落库）。
 *
 * <p>上下文按 <b>token 预算</b>管理（替代旧的「消息条数」窗口）：</p>
 * <ul>
 *   <li>{@link #load}：读回会话，若超预算则裁剪（旧数据兼容路径，内存裁剪不落库）。</li>
 *   <li>{@link #save}：只持久化 user/assistant 文本消息；末尾触发写侧裁剪
 *       （{@link #compact}），超预算部分压缩成摘要落库（SystemMessage，带
 *       {@link #SUMMARY_PREFIX} 前缀）或直接丢弃，防止 DB 无限增长。</li>
 * </ul>
 *
 * <p>token 估算与预算由 {@link ContextBudgetService} 提供（按模型自适应标定）。</p>
 */
@Slf4j
@Component
public class ConversationMemory {

    /** 已落库摘要消息的文本前缀标记，用于 load/compact 识别，避免被再次摘要。 */
    public static final String SUMMARY_PREFIX = "【会话摘要】";

    private final PgChatMemory chatMemory;
    private final ChatModel chatModel;
    private final ConversationMemoryProperties props;
    private final ContextBudgetService budgetService;

    public ConversationMemory(PgChatMemory chatMemory,
                              ChatModel chatModel,
                              ConversationMemoryProperties props,
                              ContextBudgetService budgetService) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.props = props;
        this.budgetService = budgetService;
    }

    /**
     * 载入会话历史（超预算则裁剪）。
     *
     * @param tenant 租户命名空间（由 API Key 派生），隔离不同调用方的会话
     * @param model  本次任务使用的模型（决定 token 预算口径）
     */
    public List<Message> load(String conversationId, String tenant, String model) {
        List<Message> all = chatMemory.get(namespaced(tenant, conversationId));
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        return trimToBudget(all, model, budgetService.historyBudget(model));
    }

    /**
     * 持久化本轮新增消息（仅 user/assistant 文本），末尾触发写侧裁剪。
     *
     * @param tenant 租户命名空间（由 API Key 派生）
     * @param model  本次任务使用的模型
     */
    public void save(String conversationId, String tenant, String model, List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Message> textOnly = messages.stream()
                .filter(m -> (m instanceof UserMessage || m instanceof AssistantMessage)
                        && m.getText() != null && !m.getText().isBlank())
                .toList();
        if (textOnly.isEmpty()) {
            return;
        }
        String key = namespaced(tenant, conversationId);
        chatMemory.add(key, textOnly);
        compact(key, model);
    }

    /**
     * 清空某会话全部历史。
     */
    public void clear(String conversationId, String tenant) {
        chatMemory.clear(namespaced(tenant, conversationId));
    }

    /**
     * 写侧裁剪：历史估算 token 超过高水位时，压缩到低水位（摘要落库或直接丢弃）。
     * 保留首条 user 消息作会话列表标题锚点。
     */
    private void compact(String key, String model) {
        List<Message> all = chatMemory.get(key);
        if (all == null || all.isEmpty()) {
            return;
        }
        int budget = budgetService.historyBudget(model);
        int highWatermark = (int) Math.floor(budget * props.getHighWatermarkRatio());
        if (budgetService.estimateTokens(all, model) <= highWatermark) {
            return; // 未到高水位，不裁剪（避免每轮都做 LLM 摘要）
        }
        int lowWatermark = (int) Math.floor(budget * props.getLowWatermarkRatio());
        List<Message> trimmed = trimToBudget(all, model, lowWatermark);
        chatMemory.replace(key, trimmed);
        log.info("会话压缩完成: {}（token {} → 目标 {}）",
                key, budgetService.estimateTokens(all, model), budgetService.estimateTokens(trimmed, model));
    }

    /**
     * 裁剪到目标 token 预算。优先级：最近窗口（最高，必须放进预算）→ 摘要（次之）
     * → 首条 user 标题锚点（尽力保留，超大则丢弃）。关闭摘要则直接丢弃溢出。
     * 未超预算时原样返回。
     */
    private List<Message> trimToBudget(List<Message> all, String model, int targetBudget) {
        if (budgetService.estimateTokens(all, model) <= targetBudget) {
            return all;
        }
        List<Message> summaries = all.stream().filter(this::isSummaryMessage).toList();
        List<Message> dialogs = all.stream().filter(m -> !isSummaryMessage(m)).toList();
        Message firstUser = dialogs.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst().orElse(null);

        // 1) 最近窗口：从尾部往前填，累计 token 不超预算（标题单独处理）
        //    用身份集合记录保留的消息——Message 是 record（按值相等），重复文本必须按身份区分
        List<Message> keep = new ArrayList<>();
        Set<Message> keptByIdentity = Collections.newSetFromMap(new IdentityHashMap<>());
        int keepTokens = 0;
        for (int i = dialogs.size() - 1; i >= 0; i--) {
            Message m = dialogs.get(i);
            if (m == firstUser) {
                continue;
            }
            int t = budgetService.estimateTokens(m.getText(), model);
            if (keepTokens + t > targetBudget) {
                break;
            }
            keep.add(0, m);
            keptByIdentity.add(m);
            keepTokens += t;
        }
        // 兜底：预算小到连一条都放不下，至少保留最后一条（尽力而为，可能略超预算）
        if (keep.isEmpty() && !dialogs.isEmpty()) {
            Message last = dialogs.get(dialogs.size() - 1);
            keep.add(last);
            keptByIdentity.add(last);
        }

        // 2) 溢出 = 既非标题、也不在保留窗口内的对话（按身份判断）
        List<Message> overflow = new ArrayList<>();
        for (Message m : dialogs) {
            if (m == firstUser || keptByIdentity.contains(m)) {
                continue;
            }
            overflow.add(m);
        }

        // 3) 摘要（整合旧摘要 + 新增溢出；关闭摘要则丢弃溢出）
        String priorSummary = summaries.isEmpty()
                ? null
                : summaries.get(0).getText().substring(SUMMARY_PREFIX.length());
        String newSummary = null;
        if (props.isSummarizeOverflow() && !overflow.isEmpty()) {
            newSummary = summarize(priorSummary, overflow, model);
        }

        // 4) 重组：标题（尽力）+ 摘要 + 最近窗口
        List<Message> rebuilt = new ArrayList<>();
        if (firstUser != null
                && budgetService.estimateTokens(firstUser.getText(), model) <= targetBudget / 3) {
            rebuilt.add(firstUser); // 超大首条不值得占位，直接丢弃标题锚点
        }
        String summaryText = (newSummary != null && !newSummary.isBlank()) ? newSummary
                : (priorSummary != null && !priorSummary.isBlank() ? priorSummary : null);
        if (summaryText != null) {
            rebuilt.add(summaryMessage(summaryText));
        }
        rebuilt.addAll(keep);
        return rebuilt;
    }

    private boolean isSummaryMessage(Message m) {
        return m instanceof SystemMessage
                && m.getText() != null
                && m.getText().startsWith(SUMMARY_PREFIX);
    }

    private SystemMessage summaryMessage(String summary) {
        return new SystemMessage(SUMMARY_PREFIX + summary);
    }

    private String namespaced(String tenant, String conversationId) {
        String t = (tenant == null || tenant.isBlank()) ? "default" : tenant;
        return t + ":" + props.getNamespace() + ":" + conversationId;
    }

    /**
     * 摘要 LLM 调用：若有旧摘要，要求整合（保留旧内容 + 合并新增）。
     */
    private String summarize(String priorSummary, List<Message> delta, String model) {
        String text = delta.stream()
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) {
            return priorSummary; // 无新增可总结，复用旧摘要
        }
        int limitTokens = Math.max(props.getSummarizeInputLimit(), 1);
        if (budgetService.estimateTokens(text, model) > limitTokens) {
            int maxChars = (int) Math.floor(limitTokens * budgetService.charPerToken(model));
            text = text.substring(0, Math.min(maxChars, text.length()));
        }
        StringBuilder prompt = new StringBuilder();
        if (priorSummary != null && !priorSummary.isBlank()) {
            prompt.append("以下是本会话已有的前情摘要，请保留其内容并整合新增对话：\n")
                    .append(priorSummary).append("\n\n");
        }
        prompt.append("请把下面这段多轮对话压缩成一段简洁的中文前情摘要，")
                .append("保留关键事实、用户偏好、已执行的任务与结论，150 字以内：\n\n").append(text);
        try {
            ChatResponse response = chatModel.call(new Prompt(prompt.toString()));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                return priorSummary;
            }
            String result = response.getResult().getOutput().getText();
            return (result == null || result.isBlank()) ? priorSummary : result;
        } catch (Exception e) {
            log.warn("会话摘要生成失败，回退为旧摘要: {}", e.getMessage());
            return priorSummary;
        }
    }
}
