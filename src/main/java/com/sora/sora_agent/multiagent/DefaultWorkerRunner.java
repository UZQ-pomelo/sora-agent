package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.agent.SoraManus;
import com.sora.sora_agent.config.ModelConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 默认专家执行器：按定义构建一个 SoraManus（角色提示 + 工具白名单 + 可选模型）同步执行任务。
 *
 * <p>防递归：worker 工具集排除 {@code delegate} 与 {@code runWorkflow}，
 * 使 worker 不能再次委派/触发可能委派的工作流。</p>
 */
@Slf4j
@Component
public class DefaultWorkerRunner implements WorkerRunner {

    private static final Set<String> EXCLUDED_TOOLS = Set.of("delegate", "runWorkflow");
    private static final ToolCallbackProvider EMPTY_PROVIDER = () -> new ToolCallback[0];

    private final ChatModel chatModel;
    private final ObjectProvider<ToolCallback[]> toolProvider;
    private final ModelConfig modelConfig;

    public DefaultWorkerRunner(ChatModel chatModel,
                               ObjectProvider<ToolCallback[]> toolProvider,
                               ModelConfig modelConfig) {
        this.chatModel = chatModel;
        this.toolProvider = toolProvider;
        this.modelConfig = modelConfig;
    }

    @Override
    public String run(WorkerAgent def, String task) {
        SoraManus worker = buildWorker(def);
        log.info("专家[{}]开始执行任务，模型={}，工具数={}，maxSteps={}",
                def.getName(), worker.getLockedModel(), worker.getAvailableTools().length, worker.getMaxSteps());
        String result = worker.run(task);
        log.info("专家[{}]执行完成，结果长度={}", def.getName(), result == null ? 0 : result.length());
        return result;
    }

    /**
     * 按定义构建 worker 实例：工具白名单 + 工具硬禁令 + max-steps 限制 + 可选模型。
     * package-private 以便单元测试校验约束是否生效。
     */
    SoraManus buildWorker(WorkerAgent def) {
        ToolCallback[] filtered = filterTools(toolProvider.getIfAvailable(), def.getTools(), def.getForbiddenTools());
        String model = (def.getModel() != null && !def.getModel().isBlank())
                ? def.getModel()
                : modelConfig.getDefaultModel();
        SoraManus worker = new SoraManus(filtered, EMPTY_PROVIDER, chatModel, model);
        worker.setSystemPrompt(def.getRole());
        if (def.getMaxSteps() != null && def.getMaxSteps() > 0) {
            worker.setMaxSteps(def.getMaxSteps());
        }
        return worker;
    }

    /**
     * 工具过滤：白名单命中 + 排除禁调工具（forbidden-tools 硬禁令 + delegate/runWorkflow 防递归）。
     */
    private ToolCallback[] filterTools(ToolCallback[] all, List<String> whitelist, List<String> forbiddenTools) {
        if (all == null) {
            return new ToolCallback[0];
        }
        Set<String> forbidden = new HashSet<>(EXCLUDED_TOOLS);
        if (forbiddenTools != null) {
            forbidden.addAll(forbiddenTools);
        }
        if (whitelist == null || whitelist.isEmpty()) {
            // 空 = 全部可用，但排除禁调工具
            return Arrays.stream(all)
                    .filter(t -> !forbidden.contains(t.getToolDefinition().name()))
                    .toArray(ToolCallback[]::new);
        }
        return Arrays.stream(all)
                .filter(t -> whitelist.contains(t.getToolDefinition().name()))
                .filter(t -> !forbidden.contains(t.getToolDefinition().name()))
                .toArray(ToolCallback[]::new);
    }
}
