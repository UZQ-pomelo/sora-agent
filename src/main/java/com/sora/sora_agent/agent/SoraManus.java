package com.sora.sora_agent.agent;

import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.stereotype.Component;

@Component
public class SoraManus extends ToolCallAgent {

    public SoraManus(ToolCallback[] allTools,
                     ToolCallbackProvider toolCallbacks,
                     ChatModel dashscopeChatModel) {
        super(mergeTools(allTools, toolCallbacks));
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
        // 初始化客户端
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    /**
     * 合并本地工具和 MCP 工具，返回统一的 ToolCallback 数组。
     * <p>
     * MCP 工具由 Spring AI 根据 mcp-servers.json 配置自动装配为 ToolCallbackProvider，
     * 与本地手动注册的工具合并后，Agent 即可同时调用本地工具和远程 MCP 服务（如高德地图）。
     * </p>
     */
    private static ToolCallback[] mergeTools(ToolCallback[] localTools, ToolCallbackProvider toolCallbacks) {
        ToolCallback[] mcpTools = toolCallbacks.getToolCallbacks();
        ToolCallback[] merged = new ToolCallback[localTools.length + mcpTools.length];
        System.arraycopy(localTools, 0, merged, 0, localTools.length);
        System.arraycopy(mcpTools, 0, merged, localTools.length, mcpTools.length);
        return merged;
    }
}

