package com.sora.sora_agent.agent;

import com.itextpdf.styledxmlparser.jsoup.internal.StringUtil;
import com.sora.sora_agent.agent.model.AgentState;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 抽象基础代理类，用于管理代理状态和执行流程。
 *
 * 提供状态转换、内存管理和基于步骤的执行循环的基础功能。
 * 子类必须实现step方法。
 */
@Data
@Slf4j
public abstract class BaseAgent {

    // 核心属性
    private String name;

    // 提示
    private String systemPrompt;
    private String nextStepPrompt;

    // 状态
    private AgentState state = AgentState.IDLE;

    // 执行控制
    private int maxSteps = 10;
    private int currentStep = 0;

    // 死循环检测
    private int duplicateThreshold = 2;
    private int stuckCount = 0;
    private int maxStuckCount = 2;

    // LLM
    private ChatClient chatClient;

    // Memory（需要自主维护会话上下文）
    private List<Message> messageList = new ArrayList<>();

    /**
     * 运行代理
     *
     * @param userPrompt 用户提示词
     * @return 执行结果
     */
    public String run(String userPrompt) {
        if (this.state != AgentState.IDLE) {
            throw new RuntimeException("当前agent不是空闲状态: " + this.state);
        }
        if (StringUtil.isBlank(userPrompt)) {
            throw new RuntimeException("用户输入不能为空");
        }
        // 更改状态
        state = AgentState.RUNNING;
        // 记录消息上下文
        messageList.add(new UserMessage(userPrompt));
        // 保存结果列表
        List<String> results = new ArrayList<>();
        try {
            for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                int stepNumber = i + 1;
                currentStep = stepNumber;
                log.info("执行步骤 " + stepNumber + "/" + maxSteps);
                // 单步执行
                String stepResult = step();
                String result = "Step " + stepNumber + ": " + stepResult;
                results.add(result);

                // 每步执行后检查是否陷入死循环
                if (isStuck()) {
                    stuckCount++;
                    log.warn("检测到死循环迹象，stuckCount=" + stuckCount + "/" + maxStuckCount);
                    if (stuckCount >= maxStuckCount) {
                        state = AgentState.STUCK;
                        results.add("终止：检测到死循环，已强制停止");
                        break;
                    }
                    handleStuckState();
                    results.add("⚠️ 检测到重复模式，已调整策略继续执行...");
                }
            }
            // 检查是否超出步骤限制（仅在仍为 RUNNING 状态时）
            if (state == AgentState.RUNNING && currentStep >= maxSteps) {
                state = AgentState.FINISHED;
                results.add("终止：已达到最大执行次数上限 (" + maxSteps + ")");
            }
            return String.join("\n", results);
        } catch (Exception e) {
            state = AgentState.ERROR;
            log.error("agent执行失败", e);
            return "执行错误" + e.getMessage();
        } finally {
            // 清理资源
            this.cleanup();
        }
    }

    /**
     * 运行代理（流式输出）
     *
     * @param userPrompt 用户提示词
     * @return SseEmitter实例
     */
    public SseEmitter runStream(String userPrompt) {
        // 创建SseEmitter，设置较长的超时时间
        SseEmitter emitter = new SseEmitter(300000L); // 5分钟超时

        // 使用线程异步处理，避免阻塞主线程
        CompletableFuture.runAsync(() -> {
            try {
                if (this.state != AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.state);
                    emitter.complete();
                    return;
                }
                if (StringUtil.isBlank(userPrompt)) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }

                // 更改状态
                state = AgentState.RUNNING;
                // 记录消息上下文
                messageList.add(new UserMessage(userPrompt));

                try {
                    for (int i = 0; i < maxSteps && state == AgentState.RUNNING; i++) {
                        int stepNumber = i + 1;
                        currentStep = stepNumber;
                        log.info("Executing step " + stepNumber + "/" + maxSteps);

                        // 单步执行
                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult;

                        // 发送每一步的结果
                        emitter.send(result);

                        // 每步执行后检查是否陷入死循环
                        if (isStuck()) {
                            stuckCount++;
                            log.warn("检测到死循环迹象，stuckCount=" + stuckCount + "/" + maxStuckCount);
                            if (stuckCount >= maxStuckCount) {
                                state = AgentState.STUCK;
                                emitter.send("终止：检测到死循环，已强制停止");
                                break;
                            }
                            handleStuckState();
                            emitter.send("⚠️ 检测到重复模式，已调整策略继续执行...");
                        }
                    }
                    // 检查是否超出步骤限制（仅在仍为 RUNNING 状态时）
                    if (state == AgentState.RUNNING && currentStep >= maxSteps) {
                        state = AgentState.FINISHED;
                        emitter.send("执行结束: 达到最大步骤 (" + maxSteps + ")");
                    }
                    // 发送最终 agent 状态事件给前端
                    emitter.send(SseEmitter.event()
                            .name("agent_state")
                            .data("{\"state\":\"" + state.name() + "\"}"));
                    // 正常完成
                    emitter.complete();
                } catch (Exception e) {
                    state = AgentState.ERROR;
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
                    // 清理资源
                    this.cleanup();
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        // 设置超时和完成回调
        emitter.onTimeout(() -> {
            this.state = AgentState.ERROR;
            this.cleanup();
            log.warn("SSE connection timed out");
        });

        emitter.onCompletion(() -> {
            if (this.state == AgentState.RUNNING) {
                this.state = AgentState.FINISHED;
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return emitter;
    }


    /**
     * 执行单个步骤
     *
     * @return 步骤执行结果
     */
    public abstract String step();

    /**
     * 检查代理是否陷入死循环（基于文本重复检测）。
     * 子类可重写以加入更精细的检测逻辑。
     *
     * @return 是否陷入循环
     */
    protected boolean isStuck() {
        if (messageList.size() < 2) {
            return false;
        }

        // 获取最后一条消息的内容
        Message lastMessage = messageList.get(messageList.size() - 1);

        // 从消息中提取文本内容进行比对
        String lastContent = extractMessageContent(lastMessage);
        if (lastContent == null || lastContent.isEmpty()) {
            return false;
        }

        // 计算之前消息中相同内容出现的次数
        int duplicateCount = 0;
        for (int i = messageList.size() - 2; i >= 0; i--) {
            Message msg = messageList.get(i);
            String content = extractMessageContent(msg);
            if (content != null && content.equals(lastContent)) {
                duplicateCount++;
            }
        }

        return duplicateCount >= this.duplicateThreshold;
    }

    /**
     * 从消息中提取文本内容，用于重复检测比对。
     * 支持 AssistantMessage、UserMessage 等常见类型。
     */
    private String extractMessageContent(Message message) {
        if (message == null) {
            return null;
        }
        // 优先使用 Spring AI Message 接口的 getText() 方法
        if (message instanceof org.springframework.ai.chat.messages.AssistantMessage assistantMsg) {
            return assistantMsg.getText();
        }
        if (message instanceof org.springframework.ai.chat.messages.UserMessage userMsg) {
            return userMsg.getText();
        }
        // 兜底：尝试 getContent() 或 toString()
        try {
            String content = message.getText();
            return content != null ? content : message.toString();
        } catch (Exception e) {
            return message.toString();
        }
    }

    /**
     * 处理陷入循环的状态 — 向 nextStepPrompt 注入提醒，
     * 引导 Agent 跳出重复路径。
     */
    protected void handleStuckState() {
        String stuckPrompt = "⚠️ 观察到重复响应。考虑新策略，避免重复已尝试过的无效路径。";
        this.nextStepPrompt = stuckPrompt + "\n" + (this.nextStepPrompt != null ? this.nextStepPrompt : "");
        log.warn("Agent detected stuck state. Added stuck prompt to nextStepPrompt.");
    }

    /**
     * 清理资源
     */
    protected void cleanup() {
        // 重置死循环计数器，确保下次运行从零开始
        this.stuckCount = 0;
        // 子类可以重写此方法来清理资源
    }
}

