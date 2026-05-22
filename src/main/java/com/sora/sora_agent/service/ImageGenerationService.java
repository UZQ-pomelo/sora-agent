package com.sora.sora_agent.service;

import com.alibaba.cloud.ai.dashscope.image.DashScopeImageOptions;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 图片生成服务，封装阿里云百炼 DashScope 图片模型调用。
 * <p>
 * 支持通过文字描述（文生图）生成图片，返回临时可访问的图片 URL 列表。
 * 模型默认为 {@code wan2.7-image-pro}，可在配置中覆盖。
 * </p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImageGenerationService {

    private final ImageModel imageModel;

    /**
     * 文生图，模型及默认参数通过配置 spring.ai.dashscope.image.options 指定。
     *
     * @param prompt 图片描述提示词
     * @param n      生成数量，最大 4
     * @return 图片临时 URL 列表
     */
    public List<String> generate(String prompt, int n) {
        int count = Math.min(Math.max(n, 1), 4);

        DashScopeImageOptions options = DashScopeImageOptions.builder()
                .withN(count)
                .withWidth(1024)
                .withHeight(1024)
                .build();

        log.info("开始生图, prompt: {}, n: {}", prompt, count);
        ImageResponse response = imageModel.call(new ImagePrompt(prompt, options));

        List<String> urls = response.getResults().stream()
                .map(r -> r.getOutput().getUrl())
                .toList();
        log.info("生图完成, 数量: {}", urls.size());
        return urls;
    }
}
