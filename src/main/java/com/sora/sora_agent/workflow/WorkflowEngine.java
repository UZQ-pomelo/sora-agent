package com.sora.sora_agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.common.BaseResponse;
import com.sora.sora_agent.config.WorkflowProperties;
import com.sora.sora_agent.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工作流执行引擎：按定义的线性步骤依次执行（tool / llm 两种类型），
 * 步骤间通过模板变量传数据，任一步失败即终止（fail-fast）。
 *
 * <p>同步通道 {@link #runSync} 供 agent 工具使用；流式通道 {@link #runStream}
 * 供 SSE 接口使用，逐步发送 {@code workflow_started / step_started / step_finished /
 * workflow_finished} 事件。</p>
 *
 * <p>通过 {@link ObjectProvider} 懒解析工具注册表，避免与 {@code ToolRegistration}
 * （本引擎的工具来源）形成构造循环。工具步不可调用 runWorkflow 自身（防递归）。</p>
 */
@Slf4j
@Component
public class WorkflowEngine {

    private static final Pattern TEMPLATE_PATTERN =
            Pattern.compile("\\{\\{\\s*(input|steps)\\.([a-zA-Z0-9_-]+)(?:\\.result)?\\s*\\}\\}");
    private static final String NON_INVOCABLE_TOOL = "runWorkflow";

    private final WorkflowLoader workflowLoader;
    private final ChatModel chatModel;
    private final ObjectProvider<ToolCallback[]> toolProvider;
    private final ObjectMapper objectMapper;
    private final ExecutorService agentExecutor;
    private final WorkflowProperties workflowProperties;
    /** 工具步超时用：虚拟线程跑挂起工具，超时后主流程继续，不占平台线程 */
    private final ExecutorService stepExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public WorkflowEngine(WorkflowLoader workflowLoader, ChatModel chatModel,
                          ObjectProvider<ToolCallback[]> toolProvider, ObjectMapper objectMapper,
                          ExecutorService agentExecutor, WorkflowProperties workflowProperties) {
        this.workflowLoader = workflowLoader;
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
        this.objectMapper = objectMapper;
        this.agentExecutor = agentExecutor;
        this.workflowProperties = workflowProperties;
    }

    /**
     * 同步执行工作流，返回可读的执行摘要（agent 工具通道用）。
     */
    public String runSync(String workflowName, Map<String, Object> input) {
        WorkflowExecution execution = execute(workflowName, input, null);
        return formatSummary(execution);
    }

    /**
     * 流式执行工作流，逐步发送 SSE 事件。
     */
    public SseEmitter runStream(String workflowName, Map<String, Object> input) {
        SseEmitter emitter = new SseEmitter(300000L);
        CompletableFuture.runAsync(() -> {
            try {
                execute(workflowName, input, new SseStepListener(emitter, objectMapper));
                emitter.complete();
            } catch (Exception e) {
                log.error("工作流执行失败: {}", e.getMessage());
                try {
                    String json = objectMapper.writeValueAsString(
                            BaseResponse.error(ErrorCode.BUSINESS_ERROR.getCode(), "工作流执行失败: " + e.getMessage()));
                    emitter.send(SseEmitter.event().name("error").data(json));
                } catch (IOException ex) {
                    log.warn("工作流错误事件序列化失败", ex);
                }
                emitter.complete();
            }
        }, agentExecutor);
        return emitter;
    }

    /**
     * 核心执行：按步骤线性执行，收集每步结果，任一步失败抛异常终止。
     */
    private WorkflowExecution execute(String workflowName, Map<String, Object> input, StepListener listener) {
        Workflow workflow = workflowLoader.get(workflowName);
        if (workflow == null) {
            throw new IllegalArgumentException("工作流不存在: " + workflowName
                    + "。可用工作流: " + String.join(", ", workflowLoader.names()));
        }
        Map<String, Object> safeInput = input == null ? Map.of() : input;
        Map<String, String> stepResults = new LinkedHashMap<>();
        if (listener != null) {
            listener.onWorkflowStarted(workflow.getName());
        }
        for (WorkflowStep step : workflow.getSteps()) {
            if (listener != null) {
                listener.onStepStarted(step.getId(), step.getType());
            }
            String result;
            try {
                result = executeStep(step, safeInput, stepResults);
            } catch (Exception e) {
                throw new WorkflowExecutionException("步骤[" + step.getId() + "]执行失败: " + e.getMessage(), e);
            }
            stepResults.put(step.getId(), result == null ? "" : result);
            if (listener != null) {
                listener.onStepFinished(step.getId(), result == null ? "" : result);
            }
        }
        String finalResult = workflow.getSteps().isEmpty()
                ? ""
                : stepResults.getOrDefault(workflow.getSteps().get(workflow.getSteps().size() - 1).getId(), "");
        if (listener != null) {
            listener.onWorkflowFinished(workflow.getName(), finalResult);
        }
        return new WorkflowExecution(workflow, stepResults, finalResult);
    }

    private String executeStep(WorkflowStep step, Map<String, Object> input, Map<String, String> stepResults) throws IOException {
        String type = step.getType() == null ? "" : step.getType().trim().toLowerCase();
        if ("llm".equals(type)) {
            String prompt = renderTemplate(step.getPrompt(), input, stepResults);
            ChatResponse response = chatModel.call(new Prompt(prompt));
            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                throw new IllegalStateException("模型未返回结果");
            }
            String text = response.getResult().getOutput().getText();
            return text == null ? "" : text;
        }
        if ("tool".equals(type)) {
            if (step.getTool() == null || step.getTool().isBlank()) {
                throw new IllegalArgumentException("tool 步骤缺少 tool 字段");
            }
            ToolCallback callback = findTool(step.getTool());
            if (callback == null) {
                throw new IllegalArgumentException("工具未注册: " + step.getTool());
            }
            Map<String, String> params = renderParams(step.getParams(), input, stepResults);
            String json = objectMapper.writeValueAsString(params);
            String result = callToolWithTimeout(callback, json);
            return result == null ? "" : result;
        }
        throw new IllegalArgumentException("未知步骤类型: " + step.getType());
    }

    /**
     * 带超时地调用工具：防止工具（如终端挂起进程）无限阻塞占用执行线程。
     * 超时后主流程抛错终止工作流；挂起的工具跑在虚拟线程上，不占平台线程。
     */
    private String callToolWithTimeout(ToolCallback callback, String json) {
        long timeout = Math.max(workflowProperties.getStepTimeoutSeconds(), 1L);
        try {
            return CompletableFuture.supplyAsync(() -> callback.call(json), stepExecutor)
                    .get(timeout, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException("工具[" + callback.getToolDefinition().name()
                    + "]执行超时(>" + timeout + "s)");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("工具执行被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            throw new IllegalStateException("工具执行失败: " + cause.getMessage(), cause);
        }
    }

    private ToolCallback findTool(String toolName) {
        ToolCallback[] tools = toolProvider.getIfAvailable();
        if (tools == null) {
            return null;
        }
        for (ToolCallback tool : tools) {
            String name = tool.getToolDefinition().name();
            if (name != null && name.equals(toolName) && !NON_INVOCABLE_TOOL.equals(name)) {
                return tool;
            }
        }
        return null;
    }

    private Map<String, String> renderParams(Map<String, String> params, Map<String, Object> input, Map<String, String> stepResults) {
        if (params == null) {
            return Map.of();
        }
        Map<String, String> rendered = new LinkedHashMap<>();
        params.forEach((k, v) -> rendered.put(k, renderTemplate(v, input, stepResults)));
        return rendered;
    }

    /**
     * 模板插值：替换 {@code {{input.xx}}} 与 {@code {{steps.某id.result}}}；缺失替换为空串。
     */
    private String renderTemplate(String template, Map<String, Object> input, Map<String, String> stepResults) {
        if (template == null) {
            return null;
        }
        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String type = matcher.group(1);
            String key = matcher.group(2);
            String value = "input".equals(type)
                    ? (input.get(key) == null ? "" : String.valueOf(input.get(key)))
                    : stepResults.getOrDefault(key, "");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    private String formatSummary(WorkflowExecution execution) {
        StringBuilder sb = new StringBuilder();
        sb.append("工作流「").append(execution.workflow().getName()).append("」执行完成，共 ")
                .append(execution.stepResults().size()).append(" 步：\n");
        execution.stepResults().forEach((id, result) -> {
            sb.append("- ").append(id).append(": ").append(truncate(result, 80)).append("\n");
        });
        sb.append("最终结果：\n").append(truncate(execution.finalResult(), 400));
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** 步骤执行回调（SSE 与测试共用）。 */
    public interface StepListener {
        void onWorkflowStarted(String name);

        void onStepStarted(String id, String type);

        void onStepFinished(String id, String result);

        void onWorkflowFinished(String name, String finalResult);
    }

    /** 执行结果快照。 */
    public record WorkflowExecution(Workflow workflow, Map<String, String> stepResults, String finalResult) {
    }

    /** 工作流执行失败异常。 */
    public static class WorkflowExecutionException extends RuntimeException {
        public WorkflowExecutionException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 将执行事件以命名 SSE 事件转发。 */
    private static class SseStepListener implements StepListener {

        private final SseEmitter emitter;
        private final ObjectMapper objectMapper;

        SseStepListener(SseEmitter emitter, ObjectMapper objectMapper) {
            this.emitter = emitter;
            this.objectMapper = objectMapper;
        }

        @Override
        public void onWorkflowStarted(String name) {
            send("workflow_started", Map.of("name", name));
        }

        @Override
        public void onStepStarted(String id, String type) {
            send("step_started", Map.of("id", id, "type", type));
        }

        @Override
        public void onStepFinished(String id, String result) {
            send("step_finished", Map.of("id", id, "result", result));
        }

        @Override
        public void onWorkflowFinished(String name, String finalResult) {
            send("workflow_finished", Map.of("name", name, "result", finalResult));
        }

        private void send(String eventName, Map<String, Object> data) {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(objectMapper.writeValueAsString(data)));
            } catch (IOException e) {
                log.warn("发送工作流 SSE 事件失败: {} - {}", eventName, e.getMessage());
            }
        }
    }
}
