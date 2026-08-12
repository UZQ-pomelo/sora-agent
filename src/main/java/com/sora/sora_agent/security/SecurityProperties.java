package com.sora.sora_agent.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 安全加固配置，映射 application.yml 的 app.security 节点。
 *
 * <p>YAML 结构示例：
 * <pre>
 * app:
 *   security:
 *     enabled: true
 *     api-keys: [ "sk-xxx" ]
 *     tools:
 *       file-read: false
 *       file-write: false
 *       download: false
 *       scrape: false
 *       terminal:
 *         enabled: false
 * </pre>
 * </p>
 *
 * <p>安全默认值：认证开启、危险工具全部关闭、限流开启。
 * 显式设置 {@code enabled=false} 是唯一合法的"裸奔"途径，仅限完全可信的内网。</p>
 */
@Data
@Component
@ConfigurationProperties(prefix = "app.security")
public class SecurityProperties {

    /** 是否启用 API Key 认证。显式设为 false 才关闭（存在裸奔风险）。 */
    private boolean enabled = true;

    /** 有效 API Key 列表（enabled=true 时至少配置一个，否则启动失败）。 */
    private List<String> apiKeys = new ArrayList<>();

    /** 需要认证保护的请求路径（Ant 模式，相对 context-path）。静态资源/文档默认不在其中。 */
    private List<String> protectPatterns = new ArrayList<>(List.of(
            "/ai/**", "/chat/**", "/image/**"));

    /** CORS 跨域来源配置。 */
    private Cors cors = new Cors();

    /** 危险工具开关（默认全部关闭）。 */
    private Tools tools = new Tools();

    /** 每 key 速率限制。 */
    private RateLimit rateLimit = new RateLimit();

    /** 图片代理（受信中继）配置。 */
    private ImageProxy imageProxy = new ImageProxy();

    /** 跨域来源。 */
    @Data
    public static class Cors {
        /** 允许的跨域来源（前端部署地址），仅放行精确来源、不含通配符。 */
        private List<String> allowedOrigins = new ArrayList<>(List.of(
                "http://localhost:5173", "http://127.0.0.1:5173"));
    }

    /** 危险工具开关。 */
    @Data
    public static class Tools {
        /** 文件读取工具（可读任意路径，潜在信息泄露）。 */
        private boolean fileRead = false;
        /** 文件写入工具（可写任意路径，潜在覆盖/写马）。 */
        private boolean fileWrite = false;
        /** 资源下载工具（SSRF + 写盘）。 */
        private boolean download = false;
        /** 网页抓取工具（SSRF 读取）。 */
        private boolean scrape = false;
        /** 终端工具（任意命令执行，最高风险）。 */
        private Terminal terminal = new Terminal();

        /** 终端工具细粒度配置。 */
        @Data
        public static class Terminal {
            /** 终端工具开关（默认关闭）。 */
            private boolean enabled = false;
            /** 可选命令前缀白名单；空 = 放行（显式开启工具者的责任）。 */
            private List<String> allowCommands = new ArrayList<>();
            /** 命令执行超时（秒）。 */
            private long timeoutSeconds = 30;
            /** 输出上限（字符数），超出截断。 */
            private long maxOutputBytes = 200 * 1024L;
        }
    }

    /** 速率限制。 */
    @Data
    public static class RateLimit {
        /** 是否启用每 key 滑动窗口限流。 */
        private boolean enabled = true;
        /** 每 key 每分钟最大请求数。 */
        private int perKeyPerMinute = 60;
    }

    /** 图片代理受信中继配置。 */
    @Data
    public static class ImageProxy {
        /**
         * 允许代理的图片域名白名单。
         * 支持精确域名与 {@code *.} 前缀通配（如 {@code *.aliyuncs.com}）。
         */
        private List<String> allowedHosts = new ArrayList<>(List.of(
                "dashscope-result-bj.oss-cn-beijing.aliyuncs.com",
                "*.oss-cn-beijing.aliyuncs.com"));
    }
}
