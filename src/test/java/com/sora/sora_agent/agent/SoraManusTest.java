package com.sora.sora_agent.agent;

import com.sora.sora_agent.config.ModelConfig;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SoraManusTest {

    @Resource
    private ToolCallback[] allTools;

    @Resource
    private ToolCallbackProvider toolCallbacks;

    @Resource
    private ChatModel dashscopeChatModel;

    @Resource
    private ModelConfig modelConfig;

    @Test
    void run() {
        // 使用默认模型创建 SoraManus（不走 fallback，锁定模型）
        SoraManus soraManus = new SoraManus(
                allTools, toolCallbacks, dashscopeChatModel,
                modelConfig.getDefaultModel());

        String userPrompt = """
                我想去天津和平区旅游，请你寻找天津和平区5公里内适合旅游的地方，
                并结合一些网络图片，制定一份详细的计划，
                并以 PDF 格式输出""";
        String answer = soraManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}
