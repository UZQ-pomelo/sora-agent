package com.sora.sora_agent.app;

import com.alibaba.cloud.ai.agent.Agent;
import com.alibaba.cloud.ai.dashscope.spec.DashScopeModel;
import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import com.sora.sora_agent.config.ToolConfig;
import com.sora.sora_agent.rag.TourAppVectorStoreConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
public class TourApp {

    private final ChatClient chatClient;

    @Resource
    private VectorStore tourappVectorStore;

    private static final String SYSTEM_PROMPT = "角色: "
            + "你是小途, 一位资深、温暖、极度细心的私人旅行规划专家。"
            + "你拥有全球目的地知识, 精通行程设计、交通接驳、住宿甄选、美食发掘和预算管理。"
            + "你最大的特质是绝不凭空猜测用户的喜好, 而是通过有温度的对话, "
            + "像朋友一样逐步问清需求, 然后给出真正适合对方的全面方案。"
            + "[新增能力] 你现在还是一位专业的设计师, 可以根据用户的文字描述生成精美图片。"
            + "当用户说帮我生成一张xx图片时, 你会调用 generateImage 工具来创作图片。"
            + "拿到图片URL后, 将其转换为可查看的代理链接展示给用户: "
            + "http://localhost:8080/api/image/proxy?url={URL}, "
            + "并提醒用户可以直接在浏览器中打开查看，原url也要展示出来。";

    public TourApp(ChatModel dashscopeChatModel,
                   @Qualifier("mySQLChatMemory") ChatMemory chatMemory,
                   ToolConfig toolConfig) {
        chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultSystem(SYSTEM_PROMPT)
                .defaultAdvisors(
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new MyLoggerAdvisor()
                )
                .defaultTools(toolConfig)
                .build();
    }

    /**
     * 调用chatClient对象, 传入用户prompt, 给advisor指定对话id
     */
    public String doChat(String message, String chatId) {
        ChatResponse response = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("content:{}", content);
        return content;
    }

    record TourReport(String title, List<String> suggestions) {
    }

    public TourReport doChatWithReport(String message, String chatId) {
        TourReport tourReport = chatClient
                .prompt()
                .system(SYSTEM_PROMPT + "每次对话后都要生成旅游建议结果, 标题为{用户名}的旅游建议, 内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .entity(TourReport.class);
        log.info("tourReport:{}", tourReport);
        return tourReport;
    }

    public String doChatWithRag(String message, String chatId) {
        ChatResponse chatresponse = chatClient
                .prompt()
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID,chatId))
                .advisors(new MyLoggerAdvisor())
                .advisors(QuestionAnswerAdvisor.builder(tourappVectorStore).build())
                .call()
                .chatResponse();
        String content = chatresponse.getResult().getOutput().getText();
        log.info("content:{}", content);
        return content;
    }
}
