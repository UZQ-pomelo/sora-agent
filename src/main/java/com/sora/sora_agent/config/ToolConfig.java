package com.sora.sora_agent.config;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.sora.sora_agent.service.ImageGenerationService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Description;

import java.util.List;
import java.util.function.Function;

/**
 * Spring AI Function Calling 工具注册配置。
 * <p>
 * 所有以 {@code Function<Input, Output>} 形式定义的 Bean 会被 Spring AI
 * 自动发现并转换为可供大模型调用的工具，通过 {@code defaultToolNames} 注册到 ChatClient。
 * </p>
 */
@Configuration
public class ToolConfig {

    /**
     * 文生图工具 — 当用户要求生成、绘制、创建图片时由模型自动调用。
     *
     * @param service 图片生成服务
     * @return 工具函数
     */
    @Bean
    @Description("根据文字描述生成图片。当用户要求\"画一张图\"、\"生成图片\"、\"帮我设计\"、"
            + "\"创作一幅插画\"时调用此工具。提示词应详细描述期望的画面内容，"
            + "包括风格、色彩、构图和主题。")
    public Function<ImageGenInput, ImageGenOutput> generateImage(ImageGenerationService service) {
        return input -> {
            int n = input.n > 0 ? Math.min(input.n, 4) : 1;
            List<String> urls = service.generate(input.prompt, n);
            return new ImageGenOutput(
                    urls,
                    "已成功生成 " + urls.size() + " 张图片，请将URL以可点击链接的形式展示给用户"
            );
        };
    }

    /**
     * 文生图工具输入参数。
     */
    public record ImageGenInput(
            @JsonProperty(required = true)
            @JsonPropertyDescription("图片的详细文字描述，包括风格、色彩、构图、主题等细节信息")
            String prompt,

            @JsonPropertyDescription("生成图片的数量，1到4张，默认为1")
            int n
    ) {
        public ImageGenInput {
            n = n <= 0 ? 1 : n;
        }
    }

    /**
     * 文生图工具返回结果。
     */
    public record ImageGenOutput(
            @JsonPropertyDescription("生成的图片URL列表")
            List<String> urls,

            @JsonPropertyDescription("生成状态描述")
            String status
    ) {}
}
