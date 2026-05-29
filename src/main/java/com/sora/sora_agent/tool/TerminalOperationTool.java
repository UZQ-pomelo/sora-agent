package com.sora.sora_agent.tool;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

/**
 * 基于Java的Process API实现的命令行操作工具    Windows
 */

public class TerminalOperationTool {

    @Tool(description = "在终端命令行执行命令")
    public String executeTerminalCommand(@ToolParam(description = "需要在终端执行的命令") String command) {
        StringBuilder output = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
//            Process process = Runtime.getRuntime().exec(command);
            Process process = builder.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                output.append("命令执行失败: ").append(exitCode);
            }
        } catch (IOException | InterruptedException e) {
            output.append("执行命令错误: ").append(e.getMessage());
        }
        return output.toString();
    }
}
/**
 * 以下是其他操作系统的
 */
//public class TerminalOperationTool {
//
//    @Tool(description = "在终端命令行执行命令")
//    public String executeTerminalCommand(@ToolParam(description = "需要在终端执行的命令") String command) {
//        StringBuilder output = new StringBuilder();
//        try {
//            Process process = Runtime.getRuntime().exec(command);
//            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
//                String line;
//                while ((line = reader.readLine()) != null) {
//                    output.append(line).append("\n");
//                }
//            }
//            int exitCode = process.waitFor();
//            if (exitCode != 0) {
//                output.append("命令执行失败: ").append(exitCode);
//            }
//        } catch (IOException | InterruptedException e) {
//            output.append("执行命令错误: ").append(e.getMessage());
//        }
//        return output.toString();
//    }
//}

