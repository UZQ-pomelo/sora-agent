package com.sora.sora_agent.config;

import com.sora.sora_agent.service.ImageGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 文生图工具 — 当用户要求生成图片时由 AI 模型自动调用。
 * <p>
 * 使用 {@code @Tool} 注解标记方法, Spring AI 会自动提取方法签名和参数描述
 * 生成 JSON Schema 供大模型进行 Function Calling。
 * </p>
 * <p>
 * 注册方式: {@code ChatClient.builder().defaultTools(imageGenTool)}
 * </p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolConfig {

    private final ImageGenerationService imageGenerationService;

    /**
     * 根据文字描述生成图片。
     * <p>
     * 当用户要求 "画一张图"、"生成图片"、"帮我设计"、"创作一幅插画" 时调用。
     * </p>
     *
     * @param prompt 图片的详细文字描述, 包括风格、色彩、构图、主题等细节
     * @param n      生成数量, 1~4, 默认 1
     * @return 生成结果描述, 包含图片代理链接
     */
    @Tool(description = "根据文字描述生成图片。当用户要求画图、生成图片、设计插图时调用此工具。"
            + "提示词应详细描述画面内容, 包括风格、色彩、构图和主题。")
    public String generateImage(
            @ToolParam(description = "图片的详细文字描述, 包括风格、色彩、构图、主题等细节信息") String prompt,
            @ToolParam(description = "生成图片的数量, 1到4张, 默认为1") int n) {

        int count = n > 0 ? Math.min(n, 4) : 1;
        log.info("AI 调用生图工具, prompt: {}, n: {}", prompt, count);

        List<String> urls = imageGenerationService.generate(prompt, count);

        StringBuilder result = new StringBuilder();
        result.append("已成功生成 ").append(urls.size()).append(" 张图片:\n");
        for (int i = 0; i < urls.size(); i++) {
            result.append("图片").append(i + 1).append(": ");
            result.append("http://localhost:8080/api/image/proxy?url=").append(urls.get(i));
            result.append("\n原始URL: ").append(urls.get(i)).append("\n");
        }
        result.append("请将代理链接以可点击形式展示给用户, 同时附上原始URL。");
        return result.toString();
    }
}
