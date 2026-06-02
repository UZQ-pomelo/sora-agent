package com.sora.sora_agent.controller;

import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;

/**
 * 图片接口，提供图片代理查看能力。
 * <p>
 * 由于 DashScope 生成的图片 URL 为临时链接且有跨域/防盗链限制，
 * 通过本地代理接口可直接在浏览器中打开查看。
 * </p>
 */
@Deprecated
@Slf4j
@RestController
@RequestMapping("/image")
public class ImageController {

    /**
     * 代理查看图片 — 将 DashScope 图片 URL 转为本地响应流输出。
     * <p>
     * 使用方式：浏览器访问 {@code /api/image/proxy?url=生成的图片URL}
     * </p>
     *
     * @param imageUrl DashScope 返回的图片临时 URL
     * @param response HTTP 响应
     */
    @GetMapping("/proxy")
    public void proxy(@RequestParam String imageUrl, HttpServletResponse response) {
        try {
            URI uri = URI.create(imageUrl);
            try (InputStream in = uri.toURL().openStream()) {
                response.setContentType("image/png");
                response.setHeader("Cache-Control", "public, max-age=3600");
                in.transferTo(response.getOutputStream());
                response.getOutputStream().flush();
            }
        } catch (IOException e) {
            log.error("图片代理失败, url: {}", imageUrl, e);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
        }
    }
}
