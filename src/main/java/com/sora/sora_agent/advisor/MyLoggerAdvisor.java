package com.sora.sora_agent.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientMessageAggregator;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import reactor.core.publisher.Flux;

/**
 * 自定义日志 Advisor
 * 打印 info 级别日志、只输出单次用户提示词和 AI 回复的文本
 */
@Slf4j
public class MyLoggerAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public String getName(){
        return this.getClass().getSimpleName();
    }

    @Override
    public int getOrder(){
        return 0;
    }

    /** 日志脱敏：避免完整 prompt/回复（含工具结果、敏感内容）明文落日志。 */
    private static final int LOG_MAX_CHARS = 2000;

    private ChatClientRequest before(ChatClientRequest request){
        log.info("AI Request: {}", truncate(request.prompt().getContents()));
        return request;
    }

    private void observeAfter(ChatClientResponse chatClientResponse){
        log.info("AI Response: {}", truncate(chatClientResponse.chatResponse().getResult().getOutput().getText()));
    }

    private String truncate(String s) {
        if (s == null) {
            return "null";
        }
        return s.length() <= LOG_MAX_CHARS ? s : s.substring(0, LOG_MAX_CHARS) + "…(截断)";
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest chatClientRequest, CallAdvisorChain callAdvisorChain) {
        chatClientRequest = before(chatClientRequest);
        ChatClientResponse chatClientResponse = callAdvisorChain.nextCall(chatClientRequest);
        observeAfter(chatClientResponse);
        return chatClientResponse;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest chatClientRequest, StreamAdvisorChain streamAdvisorChain) {
        chatClientRequest = before(chatClientRequest);
        Flux<ChatClientResponse> chatClientResponseFlux = streamAdvisorChain.nextStream(chatClientRequest);
        return (new ChatClientMessageAggregator()).aggregateChatClientResponse(chatClientResponseFlux,this::observeAfter);
    }
}


