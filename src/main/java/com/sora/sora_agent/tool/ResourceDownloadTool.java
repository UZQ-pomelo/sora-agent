package com.sora.sora_agent.tool;

import cn.hutool.core.io.FileUtil;
import cn.hutool.http.HttpUtil;
import com.sora.sora_agent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.File;

/**
 * 基于Hutool的下载工具
 */
public class ResourceDownloadTool {

    @Tool(description = "从url下载文件")
    public String downloadResource(@ToolParam(description = "用于下载的url") String url, @ToolParam(description = "资源下载后要保存的名字") String fileName) {
        String fileDir = FileConstant.FILE_SAVE_DIR + "/download";
        String filePath = fileDir + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(fileDir);
            // 使用 Hutool 的 downloadFile 方法下载资源
            HttpUtil.downloadFile(url, new File(filePath));
            return "资源已成功下载到: " + filePath;
        } catch (Exception e) {
            return "资源下载错误: " + e.getMessage();
        }
    }
}

