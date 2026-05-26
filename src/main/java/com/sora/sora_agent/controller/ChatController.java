package com.sora.sora_agent.controller;

import com.sora.sora_agent.app.TourApp;
import com.sora.sora_agent.common.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对话接口, 用于在 Knife4j / Swagger 中测试 AI 对话功能。
 * <p>
 * 一个 chatId 对应一段独立会话, 多次使用相同 chatId 可实现多轮对话记忆。
 * </p>
 */
@Tag(name = "对话接口", description = "与 AI 旅行助手对话, 支持多轮记忆和图片生成")
@RestController
@RequestMapping("/chat")
@RequiredArgsConstructor
public class ChatController {

    private final TourApp tourApp;

    /**
     * 发送消息并获取 AI 回复。
     * <p>
     * 不传 chatId 时会自动生成一个新的会话, 每次用同一 chatId 可保持上下文连续对话。
     * </p>
     *
     * @param message 用户消息
     * @param chatId  会话 ID (可选, 不传则自动创建新会话)
     * @return AI 回复内容
     */
    @Operation(summary = "发送对话消息", description = "向 AI 旅行助手发送消息并获取回复。传入相同的 chatId 可保持多轮对话上下文。")
    @PostMapping
    public BaseResponse<String> chat(
            @Parameter(description = "用户消息, 例如: 我想去广州旅游, 帮我规划3天行程")
            @RequestParam("message") String message,
            @Parameter(description = "会话ID (可选), 留空则自动创建新会话。同一 chatId 可连续对话保持上下文")
            @RequestParam(name = "chatId", required = false) String chatId) {

        String id = (chatId == null || chatId.isBlank())
                ? UUID.randomUUID().toString()
                : chatId;

        String answer = tourApp.doChat(message, id);
        return BaseResponse.success(answer);
    }
}
