package com.sora.sora_agent.skill;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * 技能激活工具（LLM 自主触发通道）。
 *
 * <p>实现采用「工具结果通道」：把技能指令全文作为工具结果返回，
 * 经 ToolResponseMessage 自然进入对话上下文，模型下一轮即可按指南行动 ——
 * Agent 引擎零改动。</p>
 */
public class UseSkillTool {

    private final SkillLoader skillLoader;

    public UseSkillTool(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    @Tool(description = "激活并使用指定技能。技能是预配置的专业能力，含使用指南与建议工具。"
            + "可用的技能名称见系统提示中的『可用技能』清单。")
    public String useSkill(@ToolParam(description = "要激活的技能名称") String name) {
        Skill skill = skillLoader.get(name);
        if (skill == null) {
            return "技能不存在: " + name + "。可用技能: " + String.join(", ", skillLoader.names());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【技能已激活】").append(skill.getName()).append("\n\n")
                .append(skill.getInstruction());
        if (skill.getTools() != null && !skill.getTools().isEmpty()) {
            sb.append("\n\n【建议工具】").append(String.join(", ", skill.getTools()));
        }
        sb.append("\n请严格按以上技能指南完成用户任务。");
        return sb.toString();
    }
}
