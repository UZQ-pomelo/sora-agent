package com.sora.sora_agent.tool;

import cn.hutool.core.io.FileUtil;
import com.sora.sora_agent.constant.FileConstant;
import com.sora.sora_agent.security.PathSafety;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.nio.file.Path;

/**
 * 文件操作工具，支持读取，写入文件。
 *
 * <p>安全加固：文件名统一经 {@link PathSafety} 限制在沙箱目录内，防止路径穿越。</p>
 */
public class FileOperationTool {

    private final String FILE_DIR = FileConstant.FILE_SAVE_DIR + "/file";

    @Tool(description = "从文件中读取内容")
    public String readFile(@ToolParam(description = "需要读取的文件的文件名") String fileName) {
        try {
            Path file = PathSafety.resolve(FILE_DIR, fileName);
            return FileUtil.readUtf8String(file.toFile());
        } catch (Exception e) {
            return "读取文件错误: " + e.getMessage();
        }
    }

    @Tool(description = "将内容写入文件中")
    public String writeFile(
            @ToolParam(description = "要写入的文件的文件名") String fileName,
            @ToolParam(description = "要写入文件中的内容") String content) {
        try {
            Path file = PathSafety.resolve(FILE_DIR, fileName);
            // 创建目录
            FileUtil.mkdir(FILE_DIR);
            FileUtil.writeUtf8String(content, file.toFile());
            return "成功将文件写入: " + file.toAbsolutePath();
        } catch (Exception e) {
            return "文件写入错误: " + e.getMessage();
        }
    }
}
