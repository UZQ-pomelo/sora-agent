package com.sora.sora_agent.tool;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
public class PDFGenerationToolTest {

    @Test
    public void testGeneratePDF() {
        PDFGenerationTool tool = new PDFGenerationTool();
        String fileName = "这是一个测试的PDF.pdf";
        String content = "这是PDF的内容";
        String result = tool.generatePDF(fileName, content);
        assertNotNull(result);
    }
}

