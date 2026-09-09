package com.sora.sora_agent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UrlSafety} 纯单元测试 — 不依赖 Spring 上下文。
 *
 * <p>「允许」用例使用公网 IP 字面量（如 1.1.1.1）而非域名，避免 DNS 依赖，保证离线可跑。
 * 「拒绝」用例使用私网/保留段 IP 字面量（本地解析，无需外网）。</p>
 */
class UrlSafetyTest {

    @Test
    void publicHttpsAllowed() {
        assertDoesNotThrow(() -> UrlSafety.validateHttpUrl("https://1.1.1.1/page"));
    }

    @Test
    void publicHttpAllowed() {
        assertDoesNotThrow(() -> UrlSafety.validateHttpUrl("http://8.8.8.8"));
    }

    @Test
    void localhostHostnameRejected() {
        // localhost 经 hosts 解析为回环地址，无需外网
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://localhost:8080/x"));
    }

    @Test
    void loopbackIpRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://127.0.0.1/x"));
    }

    @Test
    void cloudMetadataRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafety.validateHttpUrl("http://169.254.169.254/latest/meta-data/"));
    }

    @Test
    void privateRangesRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://10.0.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://172.16.0.1/x"));
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://192.168.1.1/x"));
    }

    @Test
    void ipv6LoopbackRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("http://[::1]/x"));
    }

    @Test
    void ftpSchemeRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("ftp://1.1.1.1/file"));
    }

    @Test
    void fileSchemeRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("file:///etc/passwd"));
    }

    @Test
    void blankOrMalformedRejected() {
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl(null));
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl(""));
        assertThrows(IllegalArgumentException.class, () -> UrlSafety.validateHttpUrl("not a url"));
    }

    @Test
    void redirectToPublicAllowed() {
        assertDoesNotThrow(() -> UrlSafety.resolveRedirect("http://1.1.1.1/final", "http://8.8.8.8/start"));
    }

    @Test
    void relativeRedirectResolvesToPublicAllowed() {
        assertDoesNotThrow(() -> UrlSafety.resolveRedirect("/final", "http://8.8.8.8/start"));
    }

    @Test
    void redirectToPrivateRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafety.resolveRedirect("http://127.0.0.1/internal", "http://8.8.8.8/start"));
    }

    @Test
    void missingLocationRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafety.resolveRedirect(null, "http://8.8.8.8/start"));
        assertThrows(IllegalArgumentException.class,
                () -> UrlSafety.resolveRedirect("  ", "http://8.8.8.8/start"));
    }
}
