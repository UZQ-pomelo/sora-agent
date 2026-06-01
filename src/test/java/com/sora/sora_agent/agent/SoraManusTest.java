package com.sora.sora_agent.agent;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class SoraManusTest {

    @Resource
    private SoraManus soraManus;

    @Test
    void run() {
        String userPrompt = """  
                我想去天津和平区旅游，请你寻找天津和平区5公里内适合旅游的地方，  
                并结合一些网络图片，制定一份详细的计划，  
                并以 PDF 格式输出""";
        String answer = soraManus.run(userPrompt);
        Assertions.assertNotNull(answer);
    }
}

