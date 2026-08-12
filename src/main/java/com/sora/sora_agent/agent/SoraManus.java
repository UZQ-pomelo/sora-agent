package com.sora.sora_agent.agent;

import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import com.sora.sora_agent.chatmemory.ConversationMemory;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@Slf4j
public class SoraManus extends ToolCallAgent {

    /** 当前任务锁定的模型名（任务期内不变） */
    @Getter
    private final String lockedModel;

    /** 会话记忆（可选；注入后支持跨请求持久化，否则维持无状态） */
    private ConversationMemory conversationMemory;

    public void setConversationMemory(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    /**
     * 构造 SoraManus 智能体。
     *
     * @param allTools       本地工具数组
     * @param toolCallbacks   MCP 工具提供者
     * @param dashscopeChatModel ChatModel（DashScope）
     * @param model           本次任务使用的模型名（如 deepseek-v4-flash），任务期内锁死不变
     */
    public SoraManus(ToolCallback[] allTools,
                     ToolCallbackProvider toolCallbacks,
                     ChatModel dashscopeChatModel,
                     String model) {
        super(mergeTools(allTools, toolCallbacks),
                com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withInternalToolExecutionEnabled(false)
                        .withModel(model)
                        .build());
        this.lockedModel = model;
        this.setName("soraManus");
        String SYSTEM_PROMPT = """
                你是 SoraManus，一个万能的 AI 助手，旨在解决用户提出的任何任务。你拥有多种工具可供调用，以高效完成复杂的请求。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具或工具组合。
                针对复杂任务，你可以将问题分解，逐步使用不同工具来解决。
                每次使用工具后，需清晰说明执行结果，并建议下一步操作。
                在调用 terminate 工具结束任务之前，你必须先输出一段完整的面向用户的最终回答（不调用任何工具），总结你为用户完成的所有工作并给出完整结果。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // ChatClient 不再设 defaultOptions — 模型通过 ToolCallAgent 的 chatOptions 注入每次 Prompt
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    /**
     * 重写流式运行，在 Agent 执行前先发送 model_info 命名事件。
     */
    @Override
    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, null);
    }

    /**
     * 带会话记忆的流式运行：启动时载入历史，结束后持久化本轮新增消息。
     * conversationId 为空时维持无状态（等价于 {@link #runStream(String)}）。
     */
    public SseEmitter runStream(String userPrompt, String conversationId) {
        SseEmitter emitter = new SseEmitter(300000L);
        boolean withMemory = conversationId != null && !conversationId.isBlank() && conversationMemory != null;

        java.util.concurrent.CompletableFuture.runAsync(() -> {
            int historySize = 0;
            try {
                if (this.getState() != com.sora.sora_agent.agent.model.AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.getState());
                    emitter.complete();
                    return;
                }
                if (userPrompt == null || userPrompt.isBlank()) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }

                // ★ 第一时间发送 model_info
                String modelInfoJson = "{\"model\":\"" + escapeJson(lockedModel)
                        + "\",\"fallback\":false}";
                emitter.send(SseEmitter.event()
                        .name("model_info")
                        .data(modelInfoJson));

                // 更改状态
                this.setState(com.sora.sora_agent.agent.model.AgentState.RUNNING);
                // 载入会话历史（若开启记忆）
                if (withMemory) {
                    List<Message> history = conversationMemory.load(conversationId);
                    if (!history.isEmpty()) {
                        this.getMessageList().addAll(history);
                        historySize = history.size();
                    }
                }
                this.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage(userPrompt));

                try {
                    for (int i = 0; i < this.getMaxSteps()
                            && this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING; i++) {
                        int stepNumber = i + 1;
                        this.setCurrentStep(stepNumber);
                        log.info("Executing step " + stepNumber + "/" + this.getMaxSteps());

                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult;
                        emitter.send(result);

                        if (isStuck()) {
                            this.setStuckCount(this.getStuckCount() + 1);
                            log.warn("检测到死循环迹象，stuckCount=" + this.getStuckCount()
                                    + "/" + this.getMaxStuckCount());
                            if (this.getStuckCount() >= this.getMaxStuckCount()) {
                                this.setState(com.sora.sora_agent.agent.model.AgentState.STUCK);
                                emitter.send("终止：检测到死循环，已强制停止");
                                break;
                            }
                            handleStuckState();
                            emitter.send("⚠️ 检测到重复模式，已调整策略继续执行...");
                        }
                    }
                    if (this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING
                            && this.getCurrentStep() >= this.getMaxSteps()) {
                        this.setState(com.sora.sora_agent.agent.model.AgentState.FINISHED);
                        emitter.send("执行结束: 达到最大步骤 (" + this.getMaxSteps() + ")");
                    }
                    emitter.send(SseEmitter.event()
                            .name("agent_state")
                            .data("{\"state\":\"" + this.getState().name() + "\"}"));
                    emitter.complete();
                } catch (Exception e) {
                    this.setState(com.sora.sora_agent.agent.model.AgentState.ERROR);
                    log.error("执行智能体失败", e);
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.send(SseEmitter.event()
                                .name("agent_state")
                                .data("{\"state\":\"ERROR\"}"));
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    this.cleanup();
                    // 持久化本轮新增消息（仅 user/assistant 文本，工具调用不落库）
                    if (withMemory) {
                        List<Message> all = this.getMessageList();
                        if (all.size() > historySize) {
                            conversationMemory.save(conversationId, all.subList(historySize, all.size()));
                        }
                    }
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        emitter.onTimeout(() -> {
            this.setState(com.sora.sora_agent.agent.model.AgentState.ERROR);
            this.cleanup();
            log.warn("SSE connection timed out");
        });

        emitter.onCompletion(() -> {
            if (this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING) {
                this.setState(com.sora.sora_agent.agent.model.AgentState.FINISHED);
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return emitter;
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 合并本地工具和 MCP 工具，返回统一的 ToolCallback 数组。
     */
    private static ToolCallback[] mergeTools(ToolCallback[] localTools, ToolCallbackProvider toolCallbacks) {
        ToolCallback[] mcpTools = toolCallbacks.getToolCallbacks();
        ToolCallback[] merged = new ToolCallback[localTools.length + mcpTools.length];
        System.arraycopy(localTools, 0, merged, 0, localTools.length);
        System.arraycopy(mcpTools, 0, merged, localTools.length, mcpTools.length);
        return merged;
    }
}
