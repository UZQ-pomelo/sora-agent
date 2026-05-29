package com.sora.sora_agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ResourceDownloadToolTest {

    @Test
    public void testDownloadResource() {
        ResourceDownloadTool tool = new ResourceDownloadTool();
        String url = "https://picsum.photos/200/300";
        String fileName = "随机图片.png";
        String result = tool.downloadResource(url, fileName);
        assertNotNull(result);
    }
}

