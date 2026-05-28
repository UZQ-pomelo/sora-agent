package com.sora.sora_agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class ExaWebSearchToolTest {

    @Value("${exa.api-key}")
    private String exaApiKey;

    @Test
    public void testSearchWeb() {
        ExaWebSearchTool tool = new ExaWebSearchTool(exaApiKey);
        String query = "bilibili哔哩哔哩";
        String result = tool.searchweb(query);
        assertNotNull(result);
    }
}

