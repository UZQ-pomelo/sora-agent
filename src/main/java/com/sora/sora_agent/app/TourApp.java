package com.sora.sora_agent.app;

import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import com.sora.sora_agent.config.ModelConfig;
import com.sora.sora_agent.config.ToolConfig;
import com.sora.sora_agent.rag.QueryRewriter;
import com.sora.sora_agent.rag.TourAppRagCustomAdvisorFactory;
import com.sora.sora_agent.service.ModelFallbackService;
import com.sora.sora_agent.service.ModelFallbackService.ModelInvokeInfo;
import com.sora.sora_agent.service.ModelFallbackService.StreamModelResult;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;

import java.util.List;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Component
@Slf4j
public class TourApp {

    private final ModelFallbackService modelFallbackService;
    private final ChatMemory chatMemory;
    private final ToolConfig toolConfig;
    private final ModelConfig modelConfig;

    @Resource
    private VectorStore tourappVectorStore;
    @Resource
    private QueryRewriter queryRewriter;
    @Resource
    private ToolCallback[] allTools;
    @Resource
    private ToolCallbackProvider toolCallbacks;

    private static final String SYSTEM_PROMPT = "角色: "
            + "你是小途, 一位资深、温暖、极度细心的私人旅行规划专家。"
            + "你拥有全球目的地知识, 精通行程设计、交通接驳、住宿甄选、美食发掘和预算管理。";

    public TourApp(ModelFallbackService modelFallbackService,
                   @Qualifier("mySQLChatMemory") ChatMemory chatMemory,
                   ToolConfig toolConfig,
                   ModelConfig modelConfig) {
        this.modelFallbackService = modelFallbackService;
        this.chatMemory = chatMemory;
        this.toolConfig = toolConfig;
        this.modelConfig = modelConfig;
    }

    /**
     * 同步对话（使用默认模型，fallback 开启）。
     */
    public String doChat(String message, String chatId) {
        return doChat(message, chatId, null);
    }

    /**
     * 同步对话（指定模型，fallback 开启）。
     */
    public String doChat(String message, String chatId, String model) {
        var result = modelFallbackService.callSync(model, spec -> configureSpec(spec, message, chatId));
        ChatResponse response = result.response();
        String content = response.getResult().getOutput().getText();
        log.info("content:{} model:{} fallback:{}", content,
                result.info().getActualModel(), result.info().isFallback());
        return content;
    }

    /**
     * 同步对话 + 返回模型信息（供需要 fallback 信息的调用方使用）。
     */
    public ModelFallbackService.SyncModelResult doChatWithModelInfo(String message, String chatId, String model) {
        return modelFallbackService.callSync(model, spec -> configureSpec(spec, message, chatId));
    }

    /**
     * 流式对话（默认模型，fallback 开启）。
     */
    public Flux<String> doChatByStream(String message, String chatId) {
        return doChatByStream(message, chatId, null);
    }

    /**
     * 流式对话（指定模型，fallback 开启）。
     *
     * @return 包含 model_info 事件（最开头）的 Flux
     */
    public StreamModelResult doChatByStreamWithInfo(String message, String chatId, String model) {
        return modelFallbackService.callStream(model, spec -> configureSpec(spec, message, chatId));
    }

    /**
     * 流式对话（指定模型，fallback 开启），仅返回文本 Flux。
     */
    public Flux<String> doChatByStream(String message, String chatId, String model) {
        return modelFallbackService.callStream(model, spec -> configureSpec(spec, message, chatId)).stream();
    }

    // ---- 结构化输出 ----

    record TourReport(String title, List<String> suggestions) {
    }

    public TourReport doChatWithReport(String message, String chatId) {
        // 结构化输出走默认模型，不做 fallback
        TourReport tourReport = buildSimpleClient()
                .prompt()
                .options(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withModel(modelConfig.getDefaultModel())
                        .build())
                .system(SYSTEM_PROMPT + "每次对话后都要生成旅游建议结果, 标题为{用户名}的旅游建议, 内容为建议列表")
                .user(message)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .call()
                .entity(TourReport.class);
        log.info("tourReport:{}", tourReport);
        return tourReport;
    }

    // ---- RAG ----

    public String doChatWithRag(String message, String chatId) {
        return doChatWithRag(message, chatId, null);
    }

    public String doChatWithRag(String message, String chatId, String model) {
        String targetModel = model != null ? model : modelConfig.getDefaultModel();
        String rewrittenMessage = queryRewriter.doQueryRewrite(message);
        ChatResponse response = buildSimpleClient()
                .prompt()
                .options(com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withModel(targetModel)
                        .build())
                .user(rewrittenMessage)
                .advisors(spec -> spec.param(CONVERSATION_ID, chatId))
                .advisors(TourAppRagCustomAdvisorFactory.createTourAppRagCustomAdvisor(tourappVectorStore))
                .call()
                .chatResponse();
        String content = response.getResult().getOutput().getText();
        log.info("RAG content:{} model:{}", content, targetModel);
        return content;
    }

    // ---- 工具调用 ----

    public String doChatWithTools(String message, String chatId) {
        return doChatWithTools(message, chatId, null);
    }

    public String doChatWithTools(String message, String chatId, String model) {
        var result = modelFallbackService.callSync(model, spec ->
                spec.user(message)
                        .advisors(a -> a.param(CONVERSATION_ID, chatId))
                        .toolCallbacks(allTools)
        );
        return result.response().getResult().getOutput().getText();
    }

    // ---- MCP ----

    public String doChatWithMcp(String message, String chatId) {
        var result = modelFallbackService.callSync(null, spec ->
                spec.user(message)
                        .advisors(a -> a.param(CONVERSATION_ID, chatId))
                        .toolCallbacks(toolCallbacks)
        );
        return result.response().getResult().getOutput().getText();
    }

    // ---- 私有方法 ----

    /**
     * 配置 ChatClient.ChatClientRequestSpec 的通用项：system prompt、user message、chatId、默认 advisors/tools。
     */
    private void configureSpec(ChatClient.ChatClientRequestSpec spec,
                               String message, String chatId) {
        spec.system(SYSTEM_PROMPT)
                .user(message)
                .advisors(a -> a.param(CONVERSATION_ID, chatId))
                .advisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                .advisors(new MyLoggerAdvisor())
                .tools(toolConfig);
    }

    /**
     * 构建一个简单的 ChatClient（不经过 fallback 链路），用于特殊场景。
     * <p>调用方需自行在 request spec 上调用 .options(DashScopeChatOptions...) 指定模型。</p>
     */
    private ChatClient buildSimpleClient() {
        return ChatClient.builder(modelFallbackService.getDashscopeChatModel()).build();
    }
}
