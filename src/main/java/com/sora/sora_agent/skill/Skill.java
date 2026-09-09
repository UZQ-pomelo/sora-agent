package com.sora.sora_agent.skill;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 技能定义（声明式能力包）。
 *
 * <p>对应 {@code skills/*.yaml} 文件内容，由 {@link SkillLoader} 解析装载：
 * <pre>
 * name: web-researcher
 * description: 联网调研与信息整理
 * instruction: |
 *   技能使用指南（多行）
 * tools:
 *   - searchweb
 *   - webScraping
 * examples:
 *   - "帮我调研 XX 的最新进展"
 * </pre>
 * </p>
 *
 * <p>软指导模型：{@code tools} 是建议工具清单（写入指令指导模型），
 * 实际工具可用性由全局注册 + {@code app.security.tools.*} 开关决定。</p>
 */
@Data
@NoArgsConstructor
public class Skill {

    /** 技能唯一标识（useSkill(name) 用） */
    private String name;

    /** 一句话描述（注入能力清单，帮助模型判断何时使用） */
    private String description;

    /** 技能使用指南（多行文本；激活后作为工具结果进入上下文） */
    private String instruction;

    /** 建议使用的工具名（软指导，非硬隔离） */
    private List<String> tools = new ArrayList<>();

    /** 触发示例（可选，辅助描述） */
    private List<String> examples = new ArrayList<>();
}
