package com.sora.sora_agent.workflow;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工作流中的单个步骤。
 *
 * <ul>
 *   <li>{@code type = llm}：用 {@code prompt}（支持模板变量）调用模型，输出文本。</li>
 *   <li>{@code type = tool}：用 {@code params}（支持模板变量）调用 {@code tool} 指定的已注册工具。</li>
 * </ul>
 */
@Data
@NoArgsConstructor
public class WorkflowStep {

    /** 步骤唯一标识（结果以 {{steps.某id.result}} 引用） */
    private String id;

    /** 步骤类型：tool | llm */
    private String type;

    /** type=tool 时：要调用的已注册工具名 */
    private String tool;

    /** type=tool 时：工具参数（值为模板字符串） */
    private Map<String, String> params = new LinkedHashMap<>();

    /** type=llm 时：prompt（支持模板变量） */
    private String prompt;
}
