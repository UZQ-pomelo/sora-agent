package com.sora.sora_agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.Map;

/**
 * 工作流运行工具（LLM 自主触发通道）。
 *
 * <p>与 {@code useSkill} 同构：把工作流作为 agent 可调用的确定性能力。
 * 工作流清单由 SoraManus 注入 systemPrompt；本工具只负责按名执行并返回执行摘要。</p>
 */
public class RunWorkflowTool {

    private final WorkflowEngine workflowEngine;
    private final ObjectMapper objectMapper;

    public RunWorkflowTool(WorkflowEngine workflowEngine, ObjectMapper objectMapper) {
        this.workflowEngine = workflowEngine;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "运行一个预定义的工作流。工作流是按固定步骤执行的确定性流程，"
            + "适合有明确标准步骤的任务（如调研→总结→生成报告）。"
            + "可用的工作流名称见系统提示中的『可用工作流』清单。")
    public String runWorkflow(
            @ToolParam(description = "要运行的工作流名称") String name,
            @ToolParam(description = "工作流入参，JSON 对象字符串，键与工作流定义的 input 对应，如 {\"topic\":\"Spring AI\"}") String input) {
        Map<String, Object> params = parseInput(input);
        try {
            return workflowEngine.runSync(name, params);
        } catch (Exception e) {
            return "工作流执行失败: " + e.getMessage();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseInput(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(input, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("工作流入参不是合法 JSON 对象: " + input);
        }
    }
}
