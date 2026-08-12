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

    public ConversationMemory(@Qualifier("mySQLChatMemory") ChatMemory chatMemory,
                              ChatModel chatModel,
                              ConversationMemoryProperties props) {
        this.chatMemory = chatMemory;
        this.chatModel = chatModel;
        this.props = props;
    }

    /**
     * 载入会话历史（窗口化 + 摘要压缩）。
     */
    public List<Message> load(String conversationId) {
        List<Message> all = chatMemory.get(namespaced(conversationId));
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
        String summary = summarize(overflow);
        if (summary == null || summary.isBlank()) {
            return keep;
        }
        List<Message> result = new ArrayList<>(keep.size() + 1);
        result.add(new SystemMessage("以下为本会话更早对话的摘要（用于保持连续性）：\n" + summary));
        result.addAll(keep);
        return result;
    }

    /**
     * 持久化本轮新增消息（仅 user/assistant 文本）。
     */
    public void save(String conversationId, List<Message> messages) {
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
        chatMemory.add(namespaced(conversationId), textOnly);
    }

    /**
     * 清空某会话全部历史。
     */
    public void clear(String conversationId) {
        chatMemory.clear(namespaced(conversationId));
    }

    private String namespaced(String conversationId) {
        return props.getNamespace() + ":" + conversationId;
    }

    private String summarize(List<Message> overflow) {
        String text = overflow.stream()
                .map(Message::getText)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining("\n"));
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() > props.getSummarizeInputLimit()) {
            text = text.substring(0, props.getSummarizeInputLimit());
        }
        try {
            String prompt = "请把下面这段多轮对话压缩成一段简洁的中文前情摘要，"
                    + "保留关键事实、用户偏好、已执行的任务与结论，150 字以内：\n\n" + text;
            ChatResponse response = chatModel.call(new Prompt(prompt));
            return response.getResult().getOutput().getText();
        } catch (Exception e) {
            log.warn("会话摘要生成失败，回退为丢弃超窗消息: {}", e.getMessage());
            return null;
        }
    }
}
