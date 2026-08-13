package com.sora.sora_agent.chatmemory;

import com.sora.sora_agent.config.ConversationMemoryProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 会话记忆服务：无状态 agent + 外部记忆。
 *
 * <ul>
 *   <li>{@link #load}：按命名空间载入会话历史；超窗时把更早部分用 LLM 压成摘要，
 *       以 SystemMessage 注入窗口首部（摘要失败安全降级为丢弃超窗部分）。</li>
 *   <li>{@link #save}：只持久化 user/assistant 文本消息（工具调用不落库）。</li>
 * </ul>
 */
@Slf4j
@Component
public class ConversationMemory {

    private final ChatMemory chatMemory;
    private final ChatModel chatModel;
    private final ConversationMemoryProperties props;

    /**
     * 摘要缓存（键 = 命名空间会话 id）：进程内复用 + 滚动补算。
     * 记录已摘要的消息条数，overflow 变化时只补算增量，避免每轮全量重算。
     */
    private final ConcurrentHashMap<String, SummaryEntry> summaryCache = new ConcurrentHashMap<>();
    private static final int SUMMARY_CACHE_MAX = 500;

    public ConversationMemory(@Qualifier("mySQLChatMemory") ChatMemory chatMemory,
                              ChatModel chatModel,
                              ConversationMemoryProperties props) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.props = props;
    }

    /**
     * 载入会话历史（窗口化 + 摘要压缩）。
     *
     * @param tenant 租户命名空间（由 API Key 派生），隔离不同调用方的会话
     */
    public List<Message> load(String conversationId, String tenant) {
        List<Message> all = chatMemory.get(namespaced(tenant, conversationId));
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        int window = Math.max(props.getWindowSize(), 1);
        if (all.size() <= window) {
            return all;
        }
        List<Message> overflow = all.subList(0, all.size() - window);
        List<Message> keep = all.subList(all.size() - window, all.size());
        if (!props.isSummarizeOverflow()) {
            return keep;
        }
        SummaryEntry entry = summarizeRolling(namespaced(tenant, conversationId), overflow);
        if (entry == null || entry.summary() == null || entry.summary().isBlank()) {
            return keep;
        }
        List<Message> result = new ArrayList<>(keep.size() + 1);
        result.add(new SystemMessage("以下为本会话更早对话的摘要（用于保持连续性）：\n" + entry.summary()));
        result.addAll(keep);
        return result;
    }

    /**
     * 持久化本轮新增消息（仅 user/assistant 文本）。
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
        chatMemory.add(namespaced(tenant, conversationId), textOnly);
    }

    /**
     * 清空某会话全部历史。
     */
    public void clear(String conversationId, String tenant) {
        summaryCache.remove(namespaced(tenant, conversationId));
        chatMemory.clear(namespaced(tenant, conversationId));
    }

    /**
     * 滚动摘要：overflow 未变化则复用缓存；变化则只对新增部分补算并与旧摘要合并。
     */
    private SummaryEntry summarizeRolling(String cacheKey, List<Message> overflow) {
        int overflowCount = overflow.size();
        SummaryEntry existing = summaryCache.get(cacheKey);
        if (existing != null && existing.summarizedCount() == overflowCount) {
            return existing; // 未变化，复用
        }
        int start = (existing == null) ? 0 : existing.summarizedCount();
        List<Message> delta = overflow.subList(start, overflowCount);
        String prior = (existing == null) ? null : existing.summary();
        String summary = summarize(prior, delta);
        if (summary == null || summary.isBlank()) {
            // 补算失败：保留旧摘要（若有），避免上下文彻底丢失
            return existing;
        }
        SummaryEntry updated = new SummaryEntry(summary, overflowCount);
        if (summaryCache.size() < SUMMARY_CACHE_MAX) {
            summaryCache.put(cacheKey, updated);
        }
        return updated;
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

    /** 摘要缓存项：摘要文本 + 已覆盖的消息条数。 */
    private record SummaryEntry(String summary, int summarizedCount) {
    }
}
