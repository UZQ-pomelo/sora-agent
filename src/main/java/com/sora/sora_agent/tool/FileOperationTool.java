package com.sora.sora_agent.tool;

import cn.hutool.core.io.FileUtil;
import com.sora.sora_agent.constant.FileConstant;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 文件操作工具，支持读取，写入文件
 */
public class FileOperationTool {

    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    @Tool(description = "从文件中读取内容")
    public String readFile(@ToolParam(description = "需要读取的文件的文件名") String fileName) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            return FileUtil.readUtf8String(filePath);
        } catch (Exception e) {
            return "读取文件错误: " + e.getMessage();
        }
    }

    @Tool(description = "将内容写入文件中")
    public String writeFile(
            @ToolParam(description = "要写入的文件的文件名") String fileName,
            @ToolParam(description = "要写入文件中的内容") String content) {
        String filePath = FILE_DIR + "/" + fileName;
        try {
            // 创建目录
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, filePath);
            return "成功将文件写入: " + filePath;
        } catch (Exception e) {
            return "文件写入错误: " + e.getMessage();
        }
    }
}

