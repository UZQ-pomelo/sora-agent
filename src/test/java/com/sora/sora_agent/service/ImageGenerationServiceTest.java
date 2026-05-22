package com.sora.sora_agent.service;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

/**
 * 图片生成服务单元测试。
 * <p>
 * 验证文生图功能是否正常, 生成完成后打印图片链接以便在浏览器中查看。
 * </p>
 */
@SpringBootTest
class ImageGenerationServiceTest {

    @Resource
    private ImageGenerationService imageGenerationService;

    @Test
    void testGenerateSingleImage() {
        List<String> urls = imageGenerationService.generate(
                "一只可爱的橘猫坐在窗台上, 阳光洒在它身上, 温馨的日系插画风格", 1);

        Assertions.assertNotNull(urls);
        Assertions.assertEquals(1, urls.size());

        String url = urls.get(0);
        Assertions.assertNotNull(url);
        Assertions.assertTrue(url.startsWith("http"));

        System.out.println("=== 生图成功, 点击链接查看 ===");
        System.out.println("原始URL: " + url);
        System.out.println("代理链接: http://localhost:8080/api/image/proxy?url=" + url);
    }

    @Test
    void testGenerateMultipleImages() {
        List<String> urls = imageGenerationService.generate(
                "桂林山水甲天下, 漓江两岸喀斯特地貌, 中国水墨画风格", 2);

        Assertions.assertNotNull(urls);
        Assertions.assertEquals(2, urls.size());

        for (int i = 0; i < urls.size(); i++) {
            String url = urls.get(i);
            Assertions.assertNotNull(url);
            Assertions.assertTrue(url.startsWith("http"));
            System.out.println("图片" + (i + 1) + " 代理链接: http://localhost:8080/api/image/proxy?url=" + url);
        }
    }
}
