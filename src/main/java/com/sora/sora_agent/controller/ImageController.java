package com.sora.sora_agent.controller;

import com.sora.sora_agent.security.SecurityProperties;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * 图片接口 — 受信中继。
 * <p>
 * 仅代理 DashScope/OSS 图片域名的 http/https 地址（域名白名单见
 * {@code app.security.image-proxy.allowed-hosts}），重定向每跳复验，
 * 带超时、内容类型校验与大小上限，杜绝 SSRF 与 file:// 本地文件读取。
 * </p>
 */
@Slf4j
@RestController
@RequestMapping("/image")
@RequiredArgsConstructor
public class ImageController {

    private static final long MAX_BYTES = 10 * 1024 * 1024L; // 10MB
    private static final int MAX_REDIRECTS = 3;
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 15000;

    private final SecurityProperties securityProperties;

    @GetMapping("/proxy")
    public void proxy(@RequestParam String imageUrl, HttpServletResponse response) {
        String current = imageUrl;
        try {
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                URI uri = URI.create(current);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                if (!("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme)) || host == null) {
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                if (!isAllowedHost(host)) {
                    response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                    return;
                }

                HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
                conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
                conn.setReadTimeout(READ_TIMEOUT_MS);
                conn.setInstanceFollowRedirects(false);
                int status = conn.getResponseCode();

                if (status >= 300 && status < 400) {
                    String location = conn.getHeaderField("Location");
                    conn.disconnect();
                    if (location == null) {
                        response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
                        return;
                    }
                    current = uri.resolve(location).toString();
                    continue;
                }

                if (status != HttpServletResponse.SC_OK) {
                    conn.disconnect();
                    response.setStatus(status);
                    return;
                }

                String contentType = conn.getContentType();
                if (contentType == null || !contentType.toLowerCase().startsWith("image/")) {
                    conn.disconnect();
                    response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
                    return;
                }
                long length = conn.getContentLengthLong();
                if (length > MAX_BYTES) {
                    conn.disconnect();
                    response.setStatus(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
                    return;
                }

                response.setContentType(contentType);
                response.setHeader("Cache-Control", "private, max-age=3600");
                try (InputStream in = conn.getInputStream()) {
                    copyWithLimit(in, response.getOutputStream(), MAX_BYTES);
                } finally {
                    conn.disconnect();
                }
                return;
            }
            response.setStatus(HttpServletResponse.SC_BAD_GATEWAY);
        } catch (Exception e) {
            log.error("图片代理失败, url: {}", imageUrl, e);
            if (!response.isCommitted()) {
                response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            }
        }
    }

    private boolean isAllowedHost(String host) {
        String h = host.toLowerCase();
        for (String pattern : securityProperties.getImageProxy().getAllowedHosts()) {
            String p = pattern.toLowerCase();
            if (p.startsWith("*.")) {
                if (h.endsWith(p.substring(1))) {
                    return true;
                }
            } else if (h.equals(p)) {
                return true;
            }
        }
        return false;
    }

    private void copyWithLimit(InputStream in, OutputStream out, long limit) throws IOException {
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            total += n;
            if (total > limit) {
                log.warn("图片代理内容超限已中断");
                return;
            }
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
