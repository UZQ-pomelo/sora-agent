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
import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话记忆服务：无状态 agent + 外部记忆（写侧裁剪 + 摘要落库）。
 *
 * <ul>
 *   <li>{@link #save}：只持久化 user/assistant 文本消息；末尾触发写侧裁剪，把超窗
 *       部分压缩成摘要落库（SystemMessage，带 {@link #SUMMARY_PREFIX} 前缀），并删除
 *       overflow 原始行，防止 DB 无限增长。</li>
 *   <li>{@link #load}：直接读回（DB 已裁剪为「标题首条 + 摘要 + 窗口消息」）；
 *       对未裁剪的旧数据，仍走内存摘要兜底（向后兼容）。</li>
 * </ul>
 */
@Slf4j
@Component
public class ConversationMemory {

    /** 已落库摘要消息的文本前缀标记，用于 load/compact 识别，避免被再次摘要。 */
    public static final String SUMMARY_PREFIX = "【会话摘要】";

    private final MySQLChatMemory chatMemory;
    private final ChatModel chatModel;
    private final ConversationMemoryProperties props;

    public ConversationMemory(MySQLChatMemory chatMemory,
                              ChatModel chatModel,
                              ConversationMemoryProperties props) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.props = props;
    }

    /**
     * 载入会话历史。
     *
     * @param tenant 租户命名空间（由 API Key 派生），隔离不同调用方的会话
     */
    public List<Message> load(String conversationId, String tenant) {
        List<Message> all = chatMemory.get(namespaced(tenant, conversationId));
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        // 已写侧裁剪（含摘要 system 消息）→ 直接返回「标题 + 摘要 + 窗口」
        if (all.stream().anyMatch(this::isSummaryMessage)) {
            return all;
        }
        // 未裁剪的旧数据：走内存摘要兜底（向后兼容）
        int window = Math.max(props.getWindowSize(), 1);
        if (all.size() <= window) {
            return all;
        }
        if (!props.isSummarizeOverflow()) {
            return all.subList(all.size() - window, all.size());
        }
        List<Message> overflow = all.subList(0, all.size() - window);
        List<Message> keep = all.subList(all.size() - window, all.size());
        String summary = summarize(null, overflow);
        if (summary == null || summary.isBlank()) {
            return keep;
        }
        List<Message> result = new ArrayList<>(keep.size() + 1);
        result.add(summaryMessage(summary));
        result.addAll(keep);
        return result;
    }

    /**
     * 持久化本轮新增消息（仅 user/assistant 文本），末尾触发写侧裁剪。
     *
     * @param tenant 租户命名空间（由 API Key 派生）
     */
    public void save(String conversationId, String tenant, List<Message> messages) {
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
        compact(key);
    }

    /**
     * 清空某会话全部历史。
     */
    public void clear(String conversationId, String tenant) {
        chatMemory.clear(namespaced(tenant, conversationId));
    }

    /**
     * 写侧裁剪：对话消息数超过 2 倍窗口时，把超窗部分压缩进摘要落库并删除原始行。
     * 保留首条 user 消息作会话列表标题锚点。
     */
    private void compact(String key) {
        if (!props.isSummarizeOverflow()) {
            return;
        }
        List<Message> all = chatMemory.get(key);
        if (all == null || all.isEmpty()) {
            return;
        }
        // 分离：已落库摘要 vs 真实对话
        List<Message> summaries = all.stream().filter(this::isSummaryMessage).toList();
        List<Message> dialogs = all.stream().filter(m -> !isSummaryMessage(m)).toList();
        int window = Math.max(props.getWindowSize(), 1);
        if (dialogs.size() <= window * 2) {
            return; // 缓冲：未超太多不裁剪，避免每轮都做 LLM 摘要
        }
        Message firstUser = dialogs.stream()
                .filter(m -> m instanceof UserMessage)
                .findFirst().orElse(null);
        List<Message> keep = dialogs.subList(dialogs.size() - window, dialogs.size());
        List<Message> overflow = new ArrayList<>(dialogs.subList(0, dialogs.size() - window));
        if (firstUser != null) {
            overflow.remove(firstUser); // 保留首条 user 作标题
        }
        String prior = summaries.isEmpty()
                ? null
                : summaries.get(0).getText().substring(SUMMARY_PREFIX.length());
        String newSummary = summarize(prior, overflow);
        // 重组：首条 user + 摘要 + 最近 window 条
        List<Message> rebuilt = new ArrayList<>();
        if (firstUser != null) {
            rebuilt.add(firstUser);
        }
        if (newSummary != null && !newSummary.isBlank()) {
            rebuilt.add(summaryMessage(newSummary));
        }
        rebuilt.addAll(keep);
        chatMemory.replace(key, rebuilt);
        log.info("会话压缩完成: {}（对话 {} 条 → 保留 {} 条 + 摘要）", key, dialogs.size(), keep.size());
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
    private String summarize(String priorSummary, List<Message> delta) {
        String text = delta.stream()
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) {
            return priorSummary; // 无新增可总结，复用旧摘要
        }
        if (text.length() > props.getSummarizeInputLimit()) {
            text = text.substring(0, props.getSummarizeInputLimit());
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
