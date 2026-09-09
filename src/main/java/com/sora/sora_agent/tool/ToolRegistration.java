package com.sora.sora_agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.multiagent.DelegateTool;
import com.sora.sora_agent.multiagent.WorkerExecutor;
import com.sora.sora_agent.security.CommandGuard;
import com.sora.sora_agent.security.SecurityProperties;
import com.sora.sora_agent.skill.SkillLoader;
import com.sora.sora_agent.skill.UseSkillTool;
import com.sora.sora_agent.workflow.RunWorkflowTool;
import com.sora.sora_agent.workflow.WorkflowEngine;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

/**
 * 本地工具注册 — 危险工具按配置开关决定是否注册（关闭 = 模型完全不可见）。
 *
 * <p>注册粒度精确到方法：文件读/写可分别开关；终端默认关闭且带资源守卫；
 * Exa 搜索缺 key 时不注册（不再导致启动失败）。关闭的工具会打 warning 日志提示。</p>
 */
@Slf4j
@Configuration
public class ToolRegistration {

    @Value("${exa.api-key:}")
    private String exaApiKey;

    private final SecurityProperties security;
    private final SkillLoader skillLoader;
    private final WorkflowEngine workflowEngine;
    private final WorkerExecutor workerExecutor;
    private final ObjectMapper objectMapper;

    public ToolRegistration(SecurityProperties security, SkillLoader skillLoader,
                            WorkflowEngine workflowEngine, WorkerExecutor workerExecutor,
                            ObjectMapper objectMapper) {
        this.security = security;
        this.skillLoader = skillLoader;
        this.workflowEngine = workflowEngine;
        this.workerExecutor = workerExecutor;
        this.objectMapper = objectMapper;
    }

    @Bean
    public ToolCallback[] allTools() {
        List<ToolCallback> tools = new ArrayList<>();

        // 文件读写（可分别开关，默认关闭）
        FileOperationTool fileOperationTool = new FileOperationTool();
        if (security.getTools().isFileRead()) {
            tools.addAll(List.of(ToolCallbacks.from(fileOperationTool, "readFile")));
        } else {
            log.warn("工具 readFile 未注册：app.security.tools.file-read 未开启");
        }
        if (security.getTools().isFileWrite()) {
            tools.addAll(List.of(ToolCallbacks.from(fileOperationTool, "writeFile")));
        } else {
            log.warn("工具 writeFile 未注册：app.security.tools.file-write 未开启");
        }

        // 终端（默认关闭；开启后带命令白名单 + 超时 + 输出上限）
        if (security.getTools().getTerminal().isEnabled()) {
            CommandGuard guard = new CommandGuard(security.getTools().getTerminal().getAllowCommands());
            TerminalOperationTool terminalTool = new TerminalOperationTool(
                    guard,
                    security.getTools().getTerminal().getTimeoutSeconds(),
                    security.getTools().getTerminal().getMaxOutputBytes());
            tools.addAll(List.of(ToolCallbacks.from(terminalTool, "executeTerminalCommand")));
        } else {
            log.warn("工具 executeTerminalCommand 未注册：app.security.tools.terminal.enabled 未开启");
        }

        // 网页抓取（默认关闭 + SSRF 防护）
        if (security.getTools().isScrape()) {
            tools.addAll(List.of(ToolCallbacks.from(new WebScrapingTool(), "scrapeWebPage")));
        } else {
            log.warn("工具 scrapeWebPage 未注册：app.security.tools.scrape 未开启");
        }

        // 资源下载（默认关闭 + SSRF/路径防护）
        if (security.getTools().isDownload()) {
            tools.addAll(List.of(ToolCallbacks.from(new ResourceDownloadTool(), "downloadResource")));
        } else {
            log.warn("工具 downloadResource 未注册：app.security.tools.download 未开启");
        }

        // Exa 搜索（缺 key 则工具不注册）
        if (exaApiKey != null && !exaApiKey.isBlank()) {
            tools.addAll(List.of(ToolCallbacks.from(new ExaWebSearchTool(exaApiKey), "searchweb")));
        } else {
            log.warn("工具 searchweb 未注册：未配置 exa.api-key");
        }

        // 无害工具恒注册
        tools.addAll(List.of(ToolCallbacks.from(new PDFGenerationTool(), "generatePDF")));
        tools.addAll(List.of(ToolCallbacks.from(new TerminateTool(), "doTerminate")));

        // 技能激活工具（恒注册，无害；skills 清单由各 agent 注入 system prompt）
        tools.addAll(List.of(ToolCallbacks.from(new UseSkillTool(skillLoader), "useSkill")));

        // 工作流运行工具（恒注册，无害；workflows 清单由各 agent 注入 system prompt）
        tools.addAll(List.of(ToolCallbacks.from(
                new RunWorkflowTool(workflowEngine, objectMapper), "runWorkflow")));

        // 多 Agent 委派工具（恒注册；专家清单由 supervisor 注入 system prompt）
        tools.addAll(List.of(ToolCallbacks.from(
                new DelegateTool(workerExecutor, objectMapper), "delegate")));

        return tools.toArray(new ToolCallback[0]);
    }
}
