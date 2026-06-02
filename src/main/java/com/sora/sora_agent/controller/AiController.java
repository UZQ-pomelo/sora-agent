package com.sora.sora_agent.controller;

import com.sora.sora_agent.app.TourApp;
import jakarta.annotation.Resource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;

@RestController
@RequestMapping("/ai")
public class AiController {

    @Resource
    private TourApp tourApp;

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ChatModel dashscopeChatModel;

    /**
     * 同步接口
     */
    @GetMapping("/tour_app/chat/sync")
    public String doChatWithTourAppSync(String message, String chatId) {
        return tourApp.doChat(message, chatId);
    }

    /**
     * SSE流式接口，Flux响应式对象，添加SSE对应MediaType
     */
    @GetMapping(value = "/tour_app/chat/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> doChatWithTourAppSSE(String message, String chatId) {
        return tourApp.doChatByStream(message, chatId);
    }

    /**
     * SSE流式接口，泛型指定为ServerSentEvent的实现
     */
    @GetMapping(value = "/tour_app/chat/server")
    public Flux<ServerSentEvent<String>> doChatWithTourAppServer(String message, String chatId) {
        return tourApp.doChatByStream(message, chatId)
                .map(chunk -> ServerSentEvent.<String>builder()
                        .data(chunk)
                        .build());
    }

    /**
     * SSE流式接口，使用SSE Emitter实现
     */
    @GetMapping("/tour_app/chat/sse/emitter")
    public SseEmitter doChatWithTourAppSseEmitter(String message, String chatId) {
        // 创建一个超时时间较长的 SseEmitter
        SseEmitter emitter = new SseEmitter(180000L); // 3分钟超时
        // 获取 Flux 数据流并直接订阅
        tourApp.doChatByStream(message, chatId)
                .subscribe(
                        // 处理每条消息
                        chunk -> {
                            try {
                                emitter.send(chunk);
                            } catch (IOException e) {
                                emitter.completeWithError(e);
                            }
                        },
                        // 处理错误
                        emitter::completeWithError,
                        // 处理完成
                        emitter::complete
                );
        // 返回emitter
        return emitter;
    }

}
