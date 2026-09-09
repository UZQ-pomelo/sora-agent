package com.sora.sora_agent.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpRequest;
import cn.hutool.http.HttpResponse;
import com.sora.sora_agent.constant.FileConstant;
import com.sora.sora_agent.security.PathSafety;
import com.sora.sora_agent.security.UrlSafety;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 基于 Hutool 的下载工具。
 *
 * <p>安全加固：SSRF 防护、重定向每跳复验、路径穿越防护、下载大小上限、超时控制。</p>
 */
public class ResourceDownloadTool {

    private static final long MAX_BYTES = 50 * 1024 * 1024L; // 50MB
    private static final int TIMEOUT_MS = 15000;
    private static final int MAX_REDIRECTS = 5;

    private final String fileDir = FileConstant.FILE_SAVE_DIR + "/download";

    @Tool(description = "从url下载文件")
    public String downloadResource(@ToolParam(description = "用于下载的url") String url,
                                   @ToolParam(description = "资源下载后要保存的名字") String fileName) {
        try {
            UrlSafety.validateHttpUrl(url);
            Path target = PathSafety.resolve(fileDir, fileName);
            FileUtil.mkdir(fileDir);

            String current = url;
            Path saved = null;
            for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
                HttpResponse resp = HttpRequest.get(current)
                        .timeout(TIMEOUT_MS)
                        .setFollowRedirects(false)
                        .execute();
                try {
                    int status = resp.getStatus();
                    if (status >= 300 && status < 400) {
                        String location = resp.header("Location");
                        current = UrlSafety.resolveRedirect(location, current);
                        continue;
                    }
                    if (status != 200) {
                        return "下载失败: HTTP " + status;
                    }
                    try (InputStream in = resp.bodyStream()) {
                        if (!copyWithLimit(in, target, MAX_BYTES)) {
                            FileUtil.del(target.toFile());
                            return "下载被拒绝: 文件超过大小上限(" + MAX_BYTES + " bytes)";
                        }
                    }
                    saved = target;
                    break;
                } finally {
                    resp.close();
                }
            }
            return saved == null ? "下载失败: 重定向次数过多" : "资源已成功下载到: " + saved.toAbsolutePath();
        } catch (Exception e) {
            return "资源下载错误: " + e.getMessage();
        }
    }

    /**
     * 带大小上限地写入目标文件。
     *
     * @return true=成功写入；false=超过上限（已终止）
     */
    private boolean copyWithLimit(InputStream in, Path target, long limit) throws Exception {
        long total = 0;
        byte[] buf = new byte[8192];
        try (OutputStream out = Files.newOutputStream(target)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
                if (total > limit) {
                    return false;
                }
                out.write(buf, 0, n);
            }
        }
        return true;
    }
}
