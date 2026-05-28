package com.sora.sora_agent.tool;

import cn.hutool.core.util.StrUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * Exa 联网搜索工具。
 * <p>
 * 通过 Exa API 执行网络搜索, 大模型在需要实时信息时会自动调用此工具。
 * 使用 highlights 模式返回查询相关摘录, Token 用量可控。
 * </p>
 */
@Slf4j
@Component
public class ExaWebSearchTool {

    private final String exaApiKey;

    private static final String SEARCH_API_URL = "https://api.exa.ai/search";
    private static final int DEFAULT_NUM_RESULTS = 5;

    public ExaWebSearchTool(@Value("${exa.api-key}") String exaApiKey) {
        this.exaApiKey = exaApiKey;
    }

    /**
     * 使用 Exa 搜索引擎进行联网搜索, 获取实时信息。
     * 当用户询问最新新闻、实时数据、近期事件等需要联网才能回答的问题时调用。
     *
     * @param query 搜索关键词或问题
     * @return 搜索结果摘要, 包含标题、链接和相关摘录
     */
    @Tool(description = "使用Exa搜索引擎进行联网搜索,获取实时信息。"
            + "当用户询问最新新闻、实时数据、近期事件等需要联网的内容时调用此工具。")
    public String searchweb(
            @ToolParam(description = "搜索关键词或问题,用自然语言描述即可") String query) {
        log.info("Exa 搜索关键词: {}", query);

        try {
            Map<String, Object> paramMap = new HashMap<>();
            paramMap.put("query", query);
            paramMap.put("type", "auto");
            paramMap.put("numResults", DEFAULT_NUM_RESULTS);

            Map<String, Object> contents = new HashMap<>();
            contents.put("highlights", true);
            paramMap.put("contents", contents);

            String requestBody = JSONUtil.toJsonStr(paramMap);

            HttpResponse response = HttpRequest.post(SEARCH_API_URL)
                    .header("x-api-key", exaApiKey)
                    .header("Content-Type", "application/json")
                    .body(requestBody)
                    .timeout(30000)
                    .execute();

            int status = response.getStatus();
            String body = response.body();

            if (status != 200) {
                log.error("Exa API 返回非 200 状态码: {}, body: {}", status, body);
                return StrUtil.format("搜索请求失败, HTTP 状态码: {}", status);
            }

            if (StrUtil.isBlank(body)) {
                return "搜索未返回任何内容";
            }

            JSONObject jsonResponse = JSONUtil.parseObj(body);
            JSONArray results = jsonResponse.getJSONArray("results");

            if (results == null || results.isEmpty()) {
                return "未找到与「" + query + "」相关的搜索结果";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("以下是与「").append(query).append("」相关的搜索结果:\n\n");

            for (int i = 0; i < results.size(); i++) {
                JSONObject result = results.getJSONObject(i);

                String title = result.getStr("title", "无标题");
                String url = result.getStr("url", "");

                sb.append("【").append(i + 1).append("】").append(title).append("\n");
                sb.append("链接: ").append(url).append("\n");

                // highlights 返回的是 JSONArray of strings
                Object highlightsObj = result.get("highlights");
                if (highlightsObj instanceof JSONArray highlights) {
                    for (int j = 0; j < highlights.size(); j++) {
                        sb.append("  > ").append(highlights.getStr(j)).append("\n");
                    }
                }

                // 有些结果可能还有 text 字段
                String text = result.getStr("text");
                if (StrUtil.isNotBlank(text)) {
                    sb.append("  摘要: ").append(text).append("\n");
                }

                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.error("调用 Exa 搜索服务时发生错误", e);
            return "搜索服务暂时不可用, 请稍后重试: " + e.getMessage();
        }
    }
}
