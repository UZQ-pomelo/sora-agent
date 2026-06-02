package com.sora.sora_agent.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.agent.SoraManus;
import com.sora.sora_agent.app.TourApp;
import com.sora.sora_agent.common.BaseResponse;
import com.sora.sora_agent.common.ThrowUtils;
import com.sora.sora_agent.exception.GlobalExceptionHandler;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@Slf4j
@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private TourApp tourApp;

    @Resource
    private ObjectMapper objectMapper;

    /**
     * 同步接口，返回统一响应格式。
     */
    @GetMapping("/tour_app/chat/sync")
    public BaseResponse<String> doChatWithTourAppSync(
            @RequestParam String message,
            @RequestParam(required = false) String chatId) {
        ThrowUtils.throwParamIf(message == null || message.isBlank(), "消息不能为空");
        String result = tourApp.doChat(message, chatId);
        return BaseResponse.success(result);
    }

    /**
     * SSE 流式接口，Flux 响应式对象，添加 SSE 对应 MediaType。
     */
    @GetMapping(value = "/tour_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithTourAppSSE(
            @RequestParam String message,
            @RequestParam(required = false) String chatId) {
        return tourApp.doChatByStream(message, chatId);
    }

    /**
     * SSE 流式接口，泛型指定为 ServerSentEvent 的实现。
     */
    @GetMapping(value = "/tour_app/chat/server")
    public Flux<ServerSentEvent<String>> doChatWithTourAppServer(
            @RequestParam String message,
            @RequestParam(required = false) String chatId) {
        return tourApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE 流式接口，使用 SSE Emitter 实现。
     * <p>
     * 流内异常通过发送一条 {@code error} 事件（包含统一错误响应 JSON）告知客户端，
     * 随后正常关闭连接。
     * </p>
     */
    @GetMapping("/tour_app/chat/sse/emitter")
    public SseEmitter doChatWithTourAppSseEmitter(
            @RequestParam String message,
            @RequestParam(required = false) String chatId) {
        SseEmitter emitter = new SseEmitter(180000L);

        tourApp.doChatByStream(message, chatId)
                .subscribe(
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        error -> {
                            // 将异常转为统一错误响应 JSON，通过 error 事件发送
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
     * 流式调用 Manus 超级智能体
     *
     * @param message
     * @return
     */
    @GetMapping("/manus/chat")
    public SseEmitter doChatWithManus(String message) {
        SoraManus soraManus = new SoraManus(allTools, toolCallbacks, dashscopeChatModel);
        return soraManus.runStream(message);
    }

}
