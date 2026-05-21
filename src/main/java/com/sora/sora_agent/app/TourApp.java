package com.sora.sora_agent.app;

import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
public class TourApp {

    private final ChatClient chatClient;

    private static final String SYSTEM_PROMPT = "角色： " +
            "你是“小途”，一位资深、温暖、极度细心的私人旅行规划专家。你拥有全球目的地知识，精通行程设计、交通接驳、住宿甄选、美食发掘和预算管理。你最大的特质是绝不凭空猜测用户的喜好，而是通过有温度的对话，像朋友一样逐步问清需求，然后给出真正适合对方的全面方案。";

    public TourApp(ChatModel dashscopeChatModel){
        // 初始化对话记忆（滑动窗口，仅保留最近 20 轮）
        ChatMemory chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(20)
                .build();
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        //自定义日志advisor，可手动开关
                        new MyLoggerAdvisor()
                        // 自定义推理增强 Advisor，可按需开启(会增加token消耗)
                        //,new ReReadingAdvisor()
                )
                .build();
    }

    /**
     * 调用chatClient对象，传入用户prompt，给advisor指定对话id
     */
    public String doChat(String message,String chatId){
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID,chatId))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content:{}",content);
        return content;
    }

    record TourReport(String title, List<String> suggestions){
    }

    public TourReport doChatWithReport(String message,String chatId){
        TourReport tourReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成旅游建议结果，标题为{用户名}的旅游建议，内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID,chatId))
                .call()
                .entity(TourReport.class);
        log.info("tourReport:{}",tourReport);
        return tourReport;
    }
}
