package com.sora.sora_agent.tool;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.IOException;

/**
 * 基于jsoup的网页抓取工具
 */
public class WebScrapingTool {

    @Tool(description = "抓取网页的内容")
    public String scrapeWebPage(@ToolParam(description = "要抓取的网页的url") String url) {
        try {
            Document doc = Jsoup.connect(url).get();
            return doc.html();
        } catch (IOException e) {
            return "抓取网页错误: " + e.getMessage();
        }
    }
}

