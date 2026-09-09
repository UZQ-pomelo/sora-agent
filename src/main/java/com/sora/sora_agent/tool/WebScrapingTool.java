package com.sora.sora_agent.tool;

import com.sora.sora_agent.security.UrlSafety;
import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

/**
 * 基于 jsoup 的网页抓取工具。
 *
 * <p>安全加固：SSRF 防护（仅 http/https + 封私网段）、重定向每跳复验、
 * 响应体大小上限、超时控制。</p>
 */
public class WebScrapingTool {

    private static final int MAX_BODY_BYTES = 2 * 1024 * 1024; // 2MB
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 5;

    @Tool(description = "抓取网页的内容")
    public String scrapeWebPage(@ToolParam(description = "要抓取的网页的url") String url) {
        try {
            UrlSafety.validateHttpUrl(url);
            String current = url;
            Document doc = null;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                Connection.Response res = Jsoup.connect(current)
                        .followRedirects(false)
                        .ignoreContentType(true)
                        .maxBodySize(MAX_BODY_BYTES)
                        .timeout(TIMEOUT_MS)
                        .execute();
                int status = res.statusCode();
                if (status >= 300 && status < 400) {
                    String location = res.header("Location");
                    current = UrlSafety.resolveRedirect(location, current);
                    continue;
                }
                if (status != 200) {
                    return "抓取失败: HTTP " + status;
                }
                doc = res.parse();
                break;
            }
            if (doc == null) {
                return "抓取失败: 重定向次数过多";
            }
            return doc.html();
        } catch (IOException e) {
            return "抓取网页错误: " + e.getMessage();
        } catch (IllegalArgumentException e) {
            return "抓取被拒绝: " + e.getMessage();
        }
    }
}
