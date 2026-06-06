package com.sora.sora_agent.agent;

import cn.hutool.core.collection.CollUtil;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions;
import com.sora.sora_agent.agent.model.AgentState;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 处理工具调用的基础代理类，具体实现了 think 和 act 方法，可以用作创建实例的父类
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public class ToolCallAgent extends ReActAgent {

    // 可用的工具
    private final ToolCallback[] availableTools;

    // 保存了工具调用信息的响应
    private ChatResponse toolCallChatResponse;

    // 工具调用管理者
    private final ToolCallingManager toolCallingManager;

    // 禁用内置的工具调用机制，自己维护上下文
    private final ChatOptions chatOptions;

    // 工具调用历史（用于死循环检测，格式：工具名::参数指纹）
    private final List<String> toolCallHistory = new ArrayList<>();
    private int consecutiveToolThreshold = 4;
    private int oscillationWindowSize = 6;
    private int oscillationMinOccurrences = 3;

    // LLM 在调用工具时同时生成的用户文本（用于 terminate+文本同一响应的场景）
    private String pendingAssistantText = null;

    public ToolCallAgent(ToolCallback[] availableTools) {
        this(availableTools, DashScopeChatOptions.builder()
                .withInternalToolExecutionEnabled(false)
                .build());
    }

    /**
     * 带自定义 ChatOptions 的构造器（供子类注入模型名等参数）。
     */
    public ToolCallAgent(ToolCallback[] availableTools, ChatOptions chatOptions) {
        super();
        this.availableTools = availableTools;
        this.toolCallingManager = ToolCallingManager.builder().build();
        // 禁用 Spring AI 内置的工具调用机制，自己维护选项和消息上下文
        this.chatOptions = chatOptions;
    }

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动
     */
    @Override
    public boolean think() {
        if (getNextStepPrompt() != null && !getNextStepPrompt().isEmpty()) {
            UserMessage userMessage = new UserMessage(getNextStepPrompt());
            getMessageList().add(userMessage);
        }
        List<Message> messageList = getMessageList();
        Prompt prompt = new Prompt(messageList, chatOptions);
        try {
            // 获取带工具选项的响应
            ChatResponse chatResponse = getChatClient().prompt(prompt)
                    .system(getSystemPrompt())
                    .toolCallbacks(availableTools)
                    .call()
                    .chatResponse();
            // 记录响应，用于 Act
            this.toolCallChatResponse = chatResponse;
            AssistantMessage assistantMessage = chatResponse.getResult().getOutput();
            // 输出提示信息
            String result = assistantMessage.getText();
            List<AssistantMessage.ToolCall> toolCallList = assistantMessage.getToolCalls();
            log.info(getName() + "的思考: " + result);
            log.info(getName() + "选择了 " + toolCallList.size() + " 个工具来使用");
            String toolCallInfo = toolCallList.stream()
                    .map(toolCall -> String.format("工具名称：%s，参数：%s",
                            toolCall.name(),
                            toolCall.arguments())
                    )
                    .collect(Collectors.joining("\n"));
            log.info(toolCallInfo);
            if (toolCallList.isEmpty()) {
                // 只有不调用工具时，才记录助手消息
                getMessageList().add(assistantMessage);
                return false;
            } else {
                // LLM 同时给出文本和工具调用 → 保存文本供 act() 使用
                pendingAssistantText = assistantMessage.getText();
                return true;
            }
        } catch (Exception e) {
            log.error(getName() + "的思考过程遇到了问题: " + e.getMessage());
            getMessageList().add(
                    new AssistantMessage("处理时遇到错误: " + e.getMessage()));
            return false;
        }
    }

    /**
     * 执行工具调用并处理结果
     *
     * @return 执行结果
     */
    @Override
    public String act() {
        if (!toolCallChatResponse.hasToolCalls()) {
            return "没有工具调用";
        }
        // 调用工具
        Prompt prompt = new Prompt(getMessageList(), chatOptions);
        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, toolCallChatResponse);
        // 记录消息上下文，conversationHistory 已经包含了助手消息和工具调用返回的结果
        setMessageList(toolExecutionResult.conversationHistory());
        // 当前工具调用的结果
        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) CollUtil.getLast(toolExecutionResult.conversationHistory());
        // 返回简洁的进度提示（原始数据已在 messageList 中供 LLM 处理）
        String results = toolResponseMessage.getResponses().stream()
                .map(response -> formatToolProgress(response.name()))
                .collect(Collectors.joining("\n"));
        // 判断是否调用了终止工具
        boolean terminateToolCalled = toolResponseMessage.getResponses().stream()
                .anyMatch(response -> "doTerminate".equals(response.name()));
        if (terminateToolCalled) {
            setState(AgentState.FINISHED);
            // 如果 LLM 在调用 terminate 时同时给出了用户文本，则直接返回文本
            if (toolResponseMessage.getResponses().size() == 1
                    && pendingAssistantText != null
                    && !pendingAssistantText.isEmpty()) {
                String text = pendingAssistantText;
                pendingAssistantText = null;
                log.info(results);
                return text;
            }
        }
        // 非终止场景下清空待处理文本
        pendingAssistantText = null;

        // 记录工具调用历史（工具名+参数指纹），用于精确死循环检测
        toolCallChatResponse.getResult().getOutput().getToolCalls().forEach(toolCall -> {
            String fingerprint = generateParamFingerprint(toolCall.arguments());
            toolCallHistory.add(toolCall.name() + "::" + fingerprint);
        });

        log.info(results);
        return results;

    }

    /**
     * 将工具名称映射为用户友好的进度提示文本。
     */
    private String formatToolProgress(String toolName) {
        return switch (toolName) {
            case "maps_geo" -> "📍 已查询地理位置";
            case "maps_around_search" -> "🔍 已搜索周边信息";
            case "maps_search_detail" -> "📋 已获取详细资料";
            case "webSearch", "exaWebSearch" -> "🌐 已搜索网页信息";
            case "webScraping" -> "📥 已抓取网页内容";
            case "generatePDF" -> "📄 已生成 PDF 文档";
            case "writeFile" -> "💾 已保存文件";
            case "resourceDownload" -> "⬇️ 已下载资源";
            case "doTerminate" -> "✅ 任务完成";
            default -> "🔧 已执行工具: " + toolName;
        };
    }

    /**
     * 重写死循环检测，在文本重复检测基础上加入工具调用级别的检测：
     * 1. 连续同工具检测 — 同一工具连续调用 ≥ consecutiveToolThreshold 次
     * 2. 振荡检测 — 最近 oscillationWindowSize 步中仅出现 2 种工具，且各出现 ≥ oscillationMinOccurrences 次
     *
     * @return 是否陷入循环
     */
    @Override
    protected boolean isStuck() {
        // 先使用父类的文本重复检测
        if (super.isStuck()) {
            return true;
        }

        // 连续同工具检测
        if (isConsecutiveSameTool()) {
            log.warn(getName() + " 检测到连续同工具循环: " + toolCallHistory.get(toolCallHistory.size() - 1));
            return true;
        }

        // 振荡模式检测
        if (isOscillating()) {
            log.warn(getName() + " 检测到工具调用振荡模式");
            return true;
        }

        return false;
    }

    /**
     * 根据工具调用参数生成短指纹，用于区分同一工具的不同参数调用。
     */
    private String generateParamFingerprint(String arguments) {
        if (arguments == null || arguments.isEmpty()) return "no-args";
        return Integer.toHexString(arguments.hashCode());
    }

    /**
     * 检测是否连续调用同一工具超过阈值。
     */
    private boolean isConsecutiveSameTool() {
        if (toolCallHistory.size() < consecutiveToolThreshold) {
            return false;
        }

        String lastTool = toolCallHistory.get(toolCallHistory.size() - 1);
        for (int i = toolCallHistory.size() - 2; i >= toolCallHistory.size() - consecutiveToolThreshold; i--) {
            if (!toolCallHistory.get(i).equals(lastTool)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 检测最近 N 步中是否仅出现 2 种工具（按工具名去参），且每种出现 ≥ oscillationMinOccurrences 次。
     */
    private boolean isOscillating() {
        if (toolCallHistory.size() < oscillationWindowSize) {
            return false;
        }

        List<String> recent = toolCallHistory.subList(
                toolCallHistory.size() - oscillationWindowSize,
                toolCallHistory.size()
        );

        // 提取工具名（去掉参数指纹）
        List<String> toolNames = recent.stream()
                .map(entry -> entry.split("::")[0])
                .collect(Collectors.toList());

        Set<String> uniqueTools = new HashSet<>(toolNames);
        if (uniqueTools.size() != 2) {
            return false;
        }

        // 窗口内仅 2 种工具，检查每种是否都出现 ≥ oscillationMinOccurrences 次
        return uniqueTools.stream().allMatch(tool ->
                Collections.frequency(toolNames, tool) >= oscillationMinOccurrences
        );
    }

    /**
     * 清理资源，包括工具调用历史。
     */
    @Override
    protected void cleanup() {
        super.cleanup();
        this.toolCallHistory.clear();
        this.pendingAssistantText = null;
    }

}

