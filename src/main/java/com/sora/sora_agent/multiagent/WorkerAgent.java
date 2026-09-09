package com.sora.sora_agent.multiagent;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 专家 worker agent 定义（声明式）。
 *
 * <p>对应 {@code agents/*.yaml} 文件内容，由 {@link WorkerAgentLoader} 解析装载：
 * <pre>
 * name: researcher
 * description: 联网调研专家
 * role: |
 *   你是一位专业的联网调研专家...
 * model: deepseek-v4-flash   # 可选，默认全局默认模型
 * tools:
 *   - searchweb
 *   - webScraping
 * </pre>
 * </p>
 */
@Data
@NoArgsConstructor
public class WorkerAgent {

    /** 专家名（delegate 工具委派目标） */
    private String name;

    /** 一句话描述（注入能力清单，帮助 supervisor 选择专家） */
    private String description;

    /** 角色系统提示（worker 的 SoraManus systemPrompt） */
    private String role;

    /** 可选：指定模型名；留空用全局默认模型 */
    private String model;

    /** 工具白名单；空 = 使用除禁调工具（delegate/runWorkflow）外的全部 */
    private List<String> tools = new ArrayList<>();

    /** 禁止调用的工具名（硬禁令，优先级高于 tools 白名单） */
    private List<String> forbiddenTools = new ArrayList<>();

    /** 可选：worker 最大步骤数；留空用框架默认（20） */
    private Integer maxSteps;
}
