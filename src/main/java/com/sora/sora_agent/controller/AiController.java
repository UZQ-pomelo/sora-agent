package com.sora.sora_agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.agent.SoraManus;
import com.sora.sora_agent.app.TourApp;
import com.sora.sora_agent.chatmemory.ConversationMemory;
import com.sora.sora_agent.common.BaseResponse;
import com.sora.sora_agent.common.ThrowUtils;
import com.sora.sora_agent.config.ModelConfig;
import com.sora.sora_agent.exception.GlobalExceptionHandler;
import com.sora.sora_agent.model.dto.ConversationSummary;
import com.sora.sora_agent.service.ConversationService;
import com.sora.sora_agent.service.ModelFallbackService;
import com.sora.sora_agent.service.ModelFallbackService.AllModelsFailedException;
import com.sora.sora_agent.multiagent.WorkerAgentLoader;
import com.sora.sora_agent.skill.SkillLoader;
import com.sora.sora_agent.workflow.WorkflowEngine;
import com.sora.sora_agent.workflow.WorkflowLoader;
import com.sora.sora_agent.service.ModelFallbackService.ModelAttempt;
import com.sora.sora_agent.service.ModelFallbackService.StreamModelResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private TourApp tourApp;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private ModelConfig modelConfig;

    @Resource
    private ModelFallbackService modelFallbackService;

    @Resource
    private ConversationMemory conversationMemory;

    @Resource
    private ConversationService conversationService;

    @Resource
    private SkillLoader skillLoader;

    @Resource
    private WorkflowLoader workflowLoader;

    @Resource
    private WorkflowEngine workflowEngine;

    @Resource
    private WorkerAgentLoader workerAgentLoader;

    @Resource
    private ExecutorService agentExecutor;

    /**
     * 获取可用模型列表。
     */
    @GetMapping("/models")
    public BaseResponse<Map<String, Object>> getModels() {
        List<Map<String, String>> models = modelConfig.getAvailable().stream()
                .map(e -> {
                    Map<String, String> m = new java.util.HashMap<>();
                    m.put("name", e.getName());
                    m.put("display", e.getDisplay());
                    return m;
                })
                .toList();
        Map<String, Object> data = new java.util.HashMap<>();
        data.put("models", models);
        data.put("default", modelConfig.getDefaultModel());
        return BaseResponse.success(data);
    }

    /**
     * 同步接口，返回统一响应格式。
     */
    @GetMapping("/tour_app/chat/sync")
    public BaseResponse<String> doChatWithTourAppSync(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String model) {
        ThrowUtils.throwParamIf(message == null || message.isBlank(), "消息不能为空");
        String result = tourApp.doChat(message, chatId, model);
        return BaseResponse.success(result);
    }

    /**
     * SSE 流式接口，Flux 响应式对象。
     */
    @GetMapping(value = "/tour_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithTourAppSSE(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String model) {
        return tourApp.doChatByStream(message, chatId, model);
    }

    /**
     * SSE 流式接口，泛型指定为 ServerSentEvent 的实现。
     * 开头注入 model_info 命名事件。
     */
    @GetMapping(value = "/tour_app/chat/server", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> doChatWithTourAppServer(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String model) {
        StreamModelResult result = tourApp.doChatByStreamWithInfo(message, chatId, model);

        // 构建 model_info ServerSentEvent
        ModelFallbackService.ModelInvokeInfo info = result.info();
        ServerSentEvent<String> modelInfoEvent = ServerSentEvent.<String>builder()
                .event("model_info")
                .data(toModelInfoJson(info))
                .build();

        // 将 model_info 事件排在流开头
        Flux<ServerSentEvent<String>> modelInfoFlux = Flux.just(modelInfoEvent);
        Flux<ServerSentEvent<String>> chatFlux = result.stream()
                .skip(1) // 跳过 ModelFallbackService 注入的原始 model_info
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());

        return Flux.concat(modelInfoFlux, chatFlux);
    }

    /**
     * SSE 流式接口，使用 SSE Emitter 实现。
     *
     * <p>首先发送 model_info 命名事件，然后逐 chunk 转发聊天流。</p>
     */
    @GetMapping("/tour_app/chat/sse/emitter")
    public SseEmitter doChatWithTourAppSseEmitter(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String model) {
        SseEmitter emitter = new SseEmitter(300000L);

        StreamModelResult result = tourApp.doChatByStreamWithInfo(message, chatId, model);
        ModelFallbackService.ModelInvokeInfo info = result.info();

        // 先发送 model_info 事件
        try {
            emitter.send(SseEmitter.event()
                    .name("model_info")
                    .data(toModelInfoJson(info)));
        } catch (IOException e) {
            emitter.completeWithError(e);
            return emitter;
        }

        // 逐 chunk 转发（跳过 model_info 前缀）
        result.stream()
                .skip(1)
                .subscribe(
                        chunk -> {
                            try {
                                // 过滤 [DONE] — SseEmitter 会在 complete() 时自然结束
                                if ("[DONE]".equals(chunk)) return;
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            BaseResponse<?> errorResp = GlobalExceptionHandler.buildErrorResponse(error);
                            try {
                                String json = objectMapper.writeValueAsString(errorResp);
                                emitter.send(SseEmitter.event().name("error").data(json));
                            } catch (IOException e) {
                                log.error("SSE 错误响应序列化失败", e);
                            }
                            emitter.complete();
                        },
                        emitter::complete
                );

        return emitter;
    }

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private ToolCallbackProvider toolCallbacks;

    /**
     * 流式调用 Manus 超级智能体（Agent 场景，无 fallback，模型在任务期内锁死）。
     *
     * @param message 用户消息
     * @param chatId  会话 id（可选；传则载入历史 + 结束后持久化，实现跨请求记忆）
     * @param model   模型名（可选，不传使用默认模型）
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(
            @RequestParam String message,
            @RequestParam(required = false) String chatId,
            @RequestParam(required = false) String model) {
        String targetModel = (model != null && !model.isBlank())
                ? model
                : modelConfig.getDefaultModel();
        SoraManus soraManus = new SoraManus(allTools, toolCallbacks, dashscopeChatModel, targetModel);
        soraManus.setConversationMemory(conversationMemory);
        soraManus.setSkillLoader(skillLoader);
        soraManus.setWorkflowLoader(workflowLoader);
        soraManus.setAgentLoader(workerAgentLoader);
        soraManus.setExecutorService(agentExecutor);
        // SoraManus.runStream() 内部已发送 model_info 事件
        return soraManus.runStream(message, chatId);
    }

    /**
     * 会话列表（供前端「对话记录」面板）。
     */
    @GetMapping("/manus/conversations")
    public BaseResponse<List<ConversationSummary>> listManusConversations() {
        return BaseResponse.success(conversationService.listConversations());
    }

    /**
     * 拉取某会话的历史消息（供切换会话后渲染）。
     */
    @GetMapping("/manus/conversations/{conversationId}/messages")
    public BaseResponse<List<Map<String, String>>> getManusConversationMessages(
            @PathVariable String conversationId) {
        List<Message> messages = conversationService.getHistory(conversationId);
        List<Map<String, String>> dto = messages.stream()
                .filter(m -> m instanceof UserMessage || m instanceof AssistantMessage)
                .map(m -> {
                    MessageType type = m.getType();
                    return Map.of(
                            "role", type == MessageType.USER ? "user" : "assistant",
                            "content", m.getText() == null ? "" : m.getText());
                })
                .toList();
        return BaseResponse.success(dto);
    }

    /**
     * 直接运行工作流（SSE 流式，逐步发送进度事件）。
     *
     * @param name  工作流名称
     * @param input 入参 JSON 对象字符串（可选），如 {"topic":"Spring AI"}
     */
    @GetMapping("/workflow/run")
    public SseEmitter runWorkflow(
            @RequestParam String name,
            @RequestParam(required = false) String input) {
        Map<String, Object> params = parseWorkflowInput(input);
        return workflowEngine.runStream(name, params);
    }

    // ---- 私有工具方法 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseWorkflowInput(String input) {
        if (input == null || input.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(input, Map.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("工作流入参不是合法 JSON 对象: " + input);
        }
    }

    private String toModelInfoJson(ModelFallbackService.ModelInvokeInfo info) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(escapeJson(info.getActualModel())).append("\"");
        sb.append(",\"fallback\":").append(info.isFallback());
        if (info.getFallbackFrom() != null) {
            sb.append(",\"fallbackFrom\":\"").append(escapeJson(info.getFallbackFrom())).append("\"");
            sb.append(",\"fallbackReason\":\"").append(escapeJson(info.getFallbackReason())).append("\"");
        }
        if (info.getAttempts() != null && !info.getAttempts().isEmpty()) {
            sb.append(",\"attempts\":[");
            boolean first = true;
            for (ModelAttempt a : info.getAttempts()) {
                if (!first) sb.append(",");
                sb.append("{\"model\":\"").append(escapeJson(a.getModel()))
                        .append("\",\"reason\":\"").append(escapeJson(a.getReason())).append("\"}");
                first = false;
            }
            sb.append("]");
        }
        sb.append("}");
        return sb.toString();
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
