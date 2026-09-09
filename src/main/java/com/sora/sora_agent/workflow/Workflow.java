package com.sora.sora_agent.workflow;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 工作流定义（声明式步骤编排）。
 *
 * <p>对应 {@code workflows/*.yaml} 文件内容，由 {@link WorkflowLoader} 解析装载：
 * <pre>
 * name: research-report
 * description: 联网调研并生成报告
 * input:
 *   - topic
 * steps:
 *   - id: search
 *     type: tool
 *     tool: searchweb
 *     params:
 *       query: "{{input.topic}} 最新进展"
 *   - id: summarize
 *     type: llm
 *     prompt: "..."
 * </pre>
 * </p>
 *
 * <p>首轮为线性步骤，仅 {@code tool} / {@code llm} 两种类型；
 * 模板变量 {@code {{input.xx}}} 引工作流入参、{@code {{steps.某id.result}}} 引上步输出。</p>
 */
@Data
@NoArgsConstructor
public class Workflow {

    /** 工作流唯一标识（runWorkflow(name) 用） */
    private String name;

    /** 一句话描述（注入能力清单，帮助 agent 判断何时使用） */
    private String description;

    /** 工作流入参名列表（文档用途；运行时以 JSON 键值传入） */
    private List<String> input = new ArrayList<>();

    /** 步骤序列（线性执行） */
    private List<WorkflowStep> steps = new ArrayList<>();
}
