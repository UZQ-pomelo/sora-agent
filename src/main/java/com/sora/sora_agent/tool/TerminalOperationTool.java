package com.sora.sora_agent.tool;

import com.sora.sora_agent.security.CommandGuard;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 终端命令执行工具（Windows，cmd.exe）。
 *
 * <p>安全加固：可选命令前缀白名单 + 强制超时 + stderr 合并防死锁 + 输出上限截断。</p>
 *
 * <p><b>风险提示</b>：终端工具是最高风险工具，默认不注册；仅在配置显式开启后可用。
 * 前缀白名单属于附加加固而非可靠防线。</p>
 */
public class TerminalOperationTool {

    private final CommandGuard commandGuard;
    private final long timeoutSeconds;
    private final long maxOutputChars;

    public TerminalOperationTool(CommandGuard commandGuard, long timeoutSeconds, long maxOutputChars) {
        this.commandGuard = commandGuard;
        this.timeoutSeconds = timeoutSeconds;
        this.maxOutputChars = maxOutputChars;
    }

    @Tool(description = "在终端命令行执行命令")
    public String executeTerminalCommand(@ToolParam(description = "需要在终端执行的命令") String command) {
        if (command == null || command.isBlank()) {
            return "执行命令错误: 命令不能为空";
        }
        if (!commandGuard.isAllowed(command)) {
            return "命令被拒绝: 不在允许的命令前缀白名单内";
        }
        try {
            ProcessBuilder builder = new ProcessBuilder("cmd.exe", "/c", command);
            builder.redirectErrorStream(true); // 合并 stderr，避免管道缓冲死锁
            Process process = builder.start();
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> outputFuture = executor.submit(() -> readOutput(process.getInputStream(), maxOutputChars));
                boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    return "命令超时(>" + timeoutSeconds + "s)，已强制终止";
                }
                // 进程已退出，输出流必然结束，直接取结果（不再设 5s 上限，避免大输出误报超时）
                String output = outputFuture.get();
                int exitCode = process.exitValue();
                String prefix = exitCode == 0 ? "" : "命令执行失败, 退出码: " + exitCode + "\n";
                return prefix + output;
            } finally {
                executor.shutdownNow();
            }
        } catch (Exception e) {
            return "执行命令错误: " + e.getMessage();
        }
    }

    private String readOutput(InputStream in, long maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        long total = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in))) {
            int n;
            while ((n = reader.read(buf)) != -1) {
                total += n;
                if (total > maxChars) {
                    return "……输出超限(" + maxChars + " 字符)已截断……";
                }
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }
}
