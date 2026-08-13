package com.sora.sora_agent.agent;

import com.sora.sora_agent.exception.AIServiceException;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;

import java.util.List;

/**
 * ReAct (Reasoning and Acting) 模式的代理抽象类
 * 实现了思考-行动的循环模式
 */
@EqualsAndHashCode(callSuper = true)
@Data
@Slf4j
public abstract class ReActAgent extends BaseAgent {

    /**
     * 处理当前状态并决定下一步行动
     *
     * @return 是否需要执行行动，true表示需要执行，false表示不需要执行
     */
    public abstract boolean think();

    /**
     * 执行决定的行动
     *
     * @return 行动执行结果
     */
    public abstract String act();

    /**
     * 执行单个步骤：思考和行动
     *
     * @return 步骤执行结果（工具进度提示 或 LLM 最终回复文本）
     */
    @Override
    public String step() {
        try {
            boolean shouldAct = think();
            if (!shouldAct) {
                // LLM 决定不再调用工具 — 提取其生成的用户友好回复
                List<Message> messages = getMessageList();
                if (!messages.isEmpty()) {
                    Message lastMsg = messages.get(messages.size() - 1);
                    if (lastMsg instanceof AssistantMessage assistantMsg) {
                        String text = assistantMsg.getText();
                        if (text != null && !text.isEmpty()) {
                            // 设置状态为已完成（LLM 已给出最终回复）
                            setState(com.sora.sora_agent.agent.model.AgentState.FINISHED);
                            return text;
                        }
                    }
                }
                return "思考完成 - 无需行动";
            }
            return act();
        } catch (AIServiceException e) {
            // 致命 LLM 错误：穿透到 runStream 置 ERROR，不让 agent 假装继续
            throw e;
        } catch (Exception e) {
            // 工具调用等可恢复错误：记录并继续下一步（agent 可换策略）
            log.warn("步骤执行失败(可恢复): {}", e.getMessage());
            return "步骤执行失败: " + e.getMessage();
        }
    }
}

