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

    @Test
    void doChatWithTools() {
        // 测试联网搜索问题的答案
        testMessage("周末想带对象去旅游，推荐几个适合情侣的小众打卡地？");

        // 测试网页抓取：情侣旅游案例分析
        testMessage("能不能帮我看看其他人对第一个打卡点的评价？");

        // 测试资源下载：图片下载
        testMessage("帮我从网上直接下载一张适合做手机壁纸的旅游景点图片为文件");

        // 测试终端操作：执行代码
        testMessage("我没有脚本，请帮我生成并执行一份 Python3 脚本来生成数据分析报告");

        // 测试文件操作：保存用户档案
        testMessage("保存我的旅游报告档案为文件");

        // 测试 PDF 生成
        testMessage("生成一份‘七夕旅游计划’PDF，包含餐厅预订、活动流程和礼物清单");
    }

    private void testMessage(String message) {
        String chatId = UUID.randomUUID().toString();
        String answer = tourapp.doChatWithTools(message, chatId);
        Assertions.assertNotNull(answer);
    }

    @Test
    void doChatWithMcp() {
        String chatId = UUID.randomUUID().toString();
        // 测试地图 MCP
        String message = "我的另一半居住在广州白云，请帮我找到 5 公里内合适的旅游约会地点";
        String answer =  tourapp.doChatWithMcp(message, chatId);
    }

    @Test
    void doChatWithMcpServer() {
        String chatId = UUID.randomUUID().toString();
        // 测试图片搜索 MCP
        String message = "帮我搜索一些旅游路上会有的花草树木图片，并给我提供他们的url";
        String answer = tourapp.doChatWithMcp(message, chatId);
        Assertions.assertNotNull(answer);
    }

}

