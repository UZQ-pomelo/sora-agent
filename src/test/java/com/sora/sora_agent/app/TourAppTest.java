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
        String message = "你好，我是koishi";
        String answer = tourapp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第二轮
        message = "我想去广州，你知道是哪里吗";
        answer = tourapp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
        // 第三轮
        message = "我刚刚说我想去哪来着？刚跟你说过，帮我回忆一下";
        answer = tourapp.doChat(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithReport() {
        String chatId = UUID.randomUUID().toString();
        // 第一轮
        String message = "你好我是koishi，我想去广州旅游，请你给我建议";
        TourApp.TourReport tourReport = tourapp.doChatWithReport(message,chatId);
        Assertions.assertNotNull(tourReport);
    }

}

