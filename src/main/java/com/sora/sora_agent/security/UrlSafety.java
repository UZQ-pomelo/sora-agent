package com.sora.sora_agent.security;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * URL 安全工具：SSRF 防护。
 *
 * <p>策略：协议仅 http/https + 解析后封禁私网/保留地址段 + 重定向每跳复验。
 * 供网页抓取、资源下载等需要访问任意公网 URL 的工具共用。</p>
 *
 * <p><b>已知局限</b>：解析后到真正建连之间存在 DNS rebinding 的 TOCTOU 缝隙，
 * 完整防护需要解析后钉扎 IP 直连（自建连接层，成本高）。框架场景先做到
 * 解析时校验 + 重定向复验这个务实水位。</p>
 */
public final class UrlSafety {

    private UrlSafety() {
    }

    /**
     * 校验 URL 可被安全访问（协议 + 主机全部 IP 均不指向私网/保留地址）。
     *
     * @throws IllegalArgumentException 协议不允许、主机缺失、解析失败或命中私网段
     */
    public static void validateHttpUrl(String url) {
        URI uri = parse(url);
        String scheme = uri.getScheme();
        if (scheme == null || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("仅允许 http/https 协议");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException("URL 缺少主机名");
        }
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("无法解析主机: " + host);
        }
        for (InetAddress address : addresses) {
            if (isBlockedAddress(address)) {
                throw new IllegalArgumentException("禁止访问内网/保留地址: " + host);
            }
        }
    }

    /**
     * 校验重定向 Location，返回可安全继续访问的绝对 URL。
     * 相对 Location 会基于当前 URL 解析后再校验。
     *
     * @param location 响应头 Location 的值
     * @param baseUrl  当前请求的 URL
     * @return 已通过校验的绝对 URL
     * @throws IllegalArgumentException Location 缺失或重定向目标不安全
     */
    public static String resolveRedirect(String location, String baseUrl) {
        if (location == null || location.isBlank()) {
            throw new IllegalArgumentException("重定向缺少 Location");
        }
        URI base = parse(baseUrl);
        URI target = base.resolve(location);
        validateHttpUrl(target.toString());
        return target.toString();
    }

    private static URI parse(String url) {
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("URL 不能为空");
        }
        try {
            return URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法 URL: " + url, e);
        }
    }

    private static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
            return true;
        }
        if (address instanceof Inet4Address) {
            int first = address.getAddress()[0] & 0xFF;
            if (first >= 224) {
                return true;                              // 组播 + 保留段
            }
            if (first == 0) {
                return true;                              // 0.0.0.0/8
            }
            if (first == 169) {
                int second = address.getAddress()[1] & 0xFF;
                if (second == 254) {
                    return true;                          // 169.254.0.0/16 链路本地 / 云元数据
                }
            }
        } else if (address instanceof Inet6Address) {
            byte[] b = address.getAddress();
            if ((b[0] & 0xFE) == 0xFC) {
                return true;                              // fc00::/7 ULA
            }
            // IPv4-mapped IPv6（::ffff:a.b.c.d）复用 IPv4 判定
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (b[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && b[10] == (byte) 0xFF && b[11] == (byte) 0xFF) {
                try {
                    Inet4Address v4 = (Inet4Address) InetAddress.getByAddress(
                            new byte[]{b[12], b[13], b[14], b[15]});
                    return isBlockedAddress(v4);
                } catch (UnknownHostException e) {
                    return true;
                }
            }
        }
        return false;
    }
}
