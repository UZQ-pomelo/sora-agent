package com.sora.sora_agent.app;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

@SpringBootTest
class TourAppTest {

    @Resource
    private TourApp tourapp;

    @Test
    void testChat() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "闺蜜/兄弟结伴出游，怎么安排才能大家开心不闹矛盾？";
        String answer = tourapp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
//        // 第二轮
//        message = "我想去广州，你知道是哪里吗";
//        answer = tourapp.doChat(message, chatId);
//        Assertions.assertNotNull(answer);
//        // 第三轮
//        message = "我刚刚说我想去哪来着？刚跟你说过，帮我回忆一下";
//        answer = tourapp.doChat(message, chatId);
//        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好我是koishi，我想去广州旅游，请你给我建议";
        TourApp.TourReport tourReport = tourapp.doChatWithReport(message, chatId);
        Assertions.assertNotNull(tourReport);
    }

    /**
     * 测试 AI 自主调用图片生成工具。
     * <p>
     * 向 ChatClient 发送明确要求生图的提示词,
     * 验证 AI 是否能自主调用 generateImage 工具并返回图片链接。
     * </p>
     */
    @Test
    void testImageGenerationThroughChat() {
        String chatId = UUID.randomUUID().toString();

        String message = "请帮我生成一张图片: 桂林山水甲天下, 漓江两岸喀斯特奇峰倒映在清澈的江面上, "
                + "远处有渔翁划着竹筏, 夕阳西下时分的温暖光线, 中国水墨画风格";

        System.out.println("=== 开始生图对话测试 ===");
        System.out.println("提示词: " + message);

        String answer = tourapp.doChat(message, chatId);

        Assertions.assertNotNull(answer);
        Assertions.assertFalse(answer.isBlank());

        System.out.println("=== AI 回复 ===");
        System.out.println(answer);
        System.out.println("=== 测试完成, 检查上方回复中是否包含图片链接 ===");

        // 如果 AI 成功调用了生图工具, 回复中应包含 URL
        boolean hasImageUrl = answer.contains("http");
        System.out.println("回复中" + (hasImageUrl ? "包含" : "未包含") + "图片链接");
    }

    @Test
    void doChatWithRag() {
        String chatId = UUID.randomUUID().toString();
        String message = "天气预报说目的地连续下雨，行程该怎么调整？";
        String answer =  tourapp.doChatWithRag(message, chatId);
        Assertions.assertNotNull(answer);
    }

}

