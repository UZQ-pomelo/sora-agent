package com.sora.sora_agent.agent;

import com.sora.sora_agent.advisor.MyLoggerAdvisor;
import com.sora.sora_agent.chatmemory.ConversationMemory;
import com.sora.sora_agent.chatmemory.ContextBudgetService;
import com.sora.sora_agent.skill.Skill;
import com.sora.sora_agent.skill.SkillLoader;
import com.sora.sora_agent.multiagent.WorkerAgent;
import com.sora.sora_agent.multiagent.WorkerAgentLoader;
import com.sora.sora_agent.workflow.Workflow;
import com.sora.sora_agent.workflow.WorkflowLoader;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;

@Slf4j
public class SoraManus extends ToolCallAgent {

    /** 当前任务锁定的模型名（任务期内不变） */
    @Getter
    private final String lockedModel;

    /** 会话记忆（可选；注入后支持跨请求持久化，否则维持无状态） */
    private ConversationMemory conversationMemory;

    /** 租户命名空间（由 API Key 派生），隔离不同调用方的会话记忆 */
    private String tenant;

    /** token 预算服务（可选；注入后启用循环内上下文裁剪 + context_usage 事件） */
    private ContextBudgetService budgetService;

    /** 固定前缀消息数（加载的历史 + 首条用户任务），循环内裁剪不触碰此前缀 */
    private int pinnedPrefixSize = 0;

    public void setConversationMemory(ConversationMemory conversationMemory) {
        this.conversationMemory = conversationMemory;
    }

    public void setTenant(String tenant) {
        this.tenant = tenant;
    }

    public void setContextBudgetService(ContextBudgetService budgetService) {
        this.budgetService = budgetService;
    }

    /** 技能体系（可选；注入后 systemPrompt 追加可用技能清单） */
    private SkillLoader skillLoader;
    private boolean skillsInjected = false;

    public void setSkillLoader(SkillLoader skillLoader) {
        this.skillLoader = skillLoader;
    }

    /** 工作流体系（可选；注入后 systemPrompt 追加可用工作流清单） */
    private WorkflowLoader workflowLoader;
    private boolean workflowsInjected = false;

    public void setWorkflowLoader(WorkflowLoader workflowLoader) {
        this.workflowLoader = workflowLoader;
    }

    /** 多 Agent 专家体系（可选；注入后 systemPrompt 追加可用专家清单） */
    private WorkerAgentLoader agentLoader;
    private boolean agentsInjected = false;

    public void setAgentLoader(WorkerAgentLoader agentLoader) {
        this.agentLoader = agentLoader;
    }

    /** 专用执行线程池（可选；注入后 agent 循环不占 JVM 公共 ForkJoinPool） */
    private ExecutorService executorService;

    public void setExecutorService(ExecutorService executorService) {
        this.executorService = executorService;
    }

    /**
     * 构造 SoraManus 智能体。
     *
     * @param allTools       本地工具数组
     * @param toolCallbacks   MCP 工具提供者
     * @param dashscopeChatModel ChatModel（DashScope）
     * @param model           本次任务使用的模型名（如 deepseek-v4-flash），任务期内锁死不变
     */
    public SoraManus(ToolCallback[] allTools,
                     ToolCallbackProvider toolCallbacks,
                     ChatModel dashscopeChatModel,
                     String model) {
        super(mergeTools(allTools, toolCallbacks),
                com.alibaba.cloud.ai.dashscope.chat.DashScopeChatOptions.builder()
                        .withInternalToolExecutionEnabled(false)
                        .withModel(model)
                        .build());
        this.lockedModel = model;
        this.setName("soraManus");
        String SYSTEM_PROMPT = """
                你是 SoraManus，一个万能的 AI 助手，旨在解决用户提出的任何任务。你拥有多种工具可供调用，以高效完成复杂的请求。
                """;
        this.setSystemPrompt(SYSTEM_PROMPT);
        String NEXT_STEP_PROMPT = """
                根据用户需求，主动选择最合适的工具或工具组合。
                针对复杂任务，你可以将问题分解，逐步使用不同工具来解决。
                每次使用工具后，需清晰说明执行结果，并建议下一步操作。
                在调用 terminate 工具结束任务之前，你必须先输出一段完整的面向用户的最终回答（不调用任何工具），总结你为用户完成的所有工作并给出完整结果。
                """;
        this.setNextStepPrompt(NEXT_STEP_PROMPT);
        this.setMaxSteps(20);
        // ChatClient 不再设 defaultOptions — 模型通过 ToolCallAgent 的 chatOptions 注入每次 Prompt
        ChatClient chatClient = ChatClient.builder(dashscopeChatModel)
                .defaultAdvisors(new MyLoggerAdvisor())
                .build();
        this.setChatClient(chatClient);
    }

    /**
     * 重写流式运行，在 Agent 执行前先发送 model_info 命名事件。
     */
    @Override
    public SseEmitter runStream(String userPrompt) {
        return runStream(userPrompt, null);
    }

    /**
     * 带会话记忆的流式运行：启动时载入历史，结束后持久化本轮新增消息。
     * conversationId 为空时维持无状态（等价于 {@link #runStream(String)}）。
     */
    public SseEmitter runStream(String userPrompt, String conversationId) {
        SseEmitter emitter = new SseEmitter(300000L);
        boolean withMemory = conversationId != null && !conversationId.isBlank() && conversationMemory != null;

        java.util.concurrent.CompletableFuture<Void> task = java.util.concurrent.CompletableFuture.runAsync(() -> {
            int historySize = 0;
            try {
                if (this.getState() != com.sora.sora_agent.agent.model.AgentState.IDLE) {
                    emitter.send("错误：无法从状态运行代理: " + this.getState());
                    emitter.complete();
                    return;
                }
                if (userPrompt == null || userPrompt.isBlank()) {
                    emitter.send("错误：不能使用空提示词运行代理");
                    emitter.complete();
                    return;
                }

                // ★ 第一时间发送 model_info
                String modelInfoJson = "{\"model\":\"" + escapeJson(lockedModel)
                        + "\",\"fallback\":false}";
                emitter.send(SseEmitter.event()
                        .name("model_info")
                        .data(modelInfoJson));

                // 更改状态
                this.setState(com.sora.sora_agent.agent.model.AgentState.RUNNING);
                // 注入可用技能清单（若注入技能体系）
                injectSkillGuide();
                // 注入可用工作流清单（若注入工作流体系）
                injectWorkflowGuide();
                // 注入可用专家清单（若注入多 Agent 体系）
                injectAgentGuide();
                // 载入会话历史（若开启记忆）
                if (withMemory) {
                    List<Message> history = conversationMemory.load(conversationId, tenant, this.lockedModel);
                    if (!history.isEmpty()) {
                        this.getMessageList().addAll(history);
                        historySize = history.size();
                    }
                }
                // 死循环比对只针对本轮新增消息，避免把加载的历史当重复
                this.setStuckCompareFrom(historySize);
                this.getMessageList().add(new org.springframework.ai.chat.messages.UserMessage(userPrompt));
                // 固定前缀 = 历史 + 首条用户任务；循环内裁剪不触碰它，保证保存逻辑的 historySize 锚点有效
                this.pinnedPrefixSize = this.getMessageList().size();

                try {
                    for (int i = 0; i < this.getMaxSteps()
                            && this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING
                            && !this.isClosed()
                            && !Thread.currentThread().isInterrupted(); i++) {
                        int stepNumber = i + 1;
                        this.setCurrentStep(stepNumber);
                        log.info("Executing step " + stepNumber + "/" + this.getMaxSteps());

                        String stepResult = step();
                        String result = "Step " + stepNumber + ": " + stepResult;
                        emitter.send(result);
                        // 每步结束后推送上下文用量（供前端实时填充条）
                        emitContextUsage(emitter, stepNumber);

                        if (isStuck()) {
                            this.setStuckCount(this.getStuckCount() + 1);
                            log.warn("检测到死循环迹象，stuckCount=" + this.getStuckCount()
                                    + "/" + this.getMaxStuckCount());
                            if (this.getStuckCount() >= this.getMaxStuckCount()) {
                                this.setState(com.sora.sora_agent.agent.model.AgentState.STUCK);
                                emitter.send("终止：检测到死循环，已强制停止");
                                break;
                            }
                            handleStuckState();
                            emitter.send("⚠️ 检测到重复模式，已调整策略继续执行...");
                        }
                    }
                    if (this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING
                            && this.getCurrentStep() >= this.getMaxSteps()) {
                        this.setState(com.sora.sora_agent.agent.model.AgentState.FINISHED);
                        emitter.send("执行结束: 达到最大步骤 (" + this.getMaxSteps() + ")");
                    }
                    emitter.send(SseEmitter.event()
                            .name("agent_state")
                            .data("{\"state\":\"" + this.getState().name() + "\"}"));
                    emitter.complete();
                } catch (Exception e) {
                    this.setState(com.sora.sora_agent.agent.model.AgentState.ERROR);
                    log.error("执行智能体失败", e);
                    try {
                        emitter.send("执行错误: " + e.getMessage());
                        emitter.send(SseEmitter.event()
                                .name("agent_state")
                                .data("{\"state\":\"ERROR\"}"));
                        emitter.complete();
                    } catch (Exception ex) {
                        emitter.completeWithError(ex);
                    }
                } finally {
                    this.cleanup();
                    // 持久化本轮新增消息（仅 user/assistant 文本，工具调用不落库；
                    // 排除内部注入的 nextStepPrompt，避免污染会话记忆）。
                    // ERROR/STUCK 状态不落库——任务未成功完成，中间态消息不进记忆。
                    if (withMemory && this.getState() != com.sora.sora_agent.agent.model.AgentState.ERROR) {
                        List<Message> all = this.getMessageList();
                        if (all.size() > historySize) {
                            List<Message> newMessages = all.subList(historySize, all.size());
                            try {
                                conversationMemory.save(conversationId, tenant, this.lockedModel, filterInternalPrompts(newMessages));
                            } catch (Exception e) {
                                log.error("会话记忆持久化失败, chatId={}: {}", conversationId, e.getMessage());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        }, executorService != null ? executorService : java.util.concurrent.ForkJoinPool.commonPool());

        emitter.onTimeout(() -> {
            this.markClosed(); // 标记关闭，agent 循环每轮检查提前退出
            this.setState(com.sora.sora_agent.agent.model.AgentState.ERROR);
            task.cancel(true); // 中断未完成的 agent 循环，避免继续调用 LLM 烧 token
            this.cleanup();
            log.warn("SSE connection timed out");
        });

        emitter.onCompletion(() -> {
            this.markClosed();
            if (this.getState() == com.sora.sora_agent.agent.model.AgentState.RUNNING) {
                this.setState(com.sora.sora_agent.agent.model.AgentState.FINISHED);
            }
            this.cleanup();
            log.info("SSE connection completed");
        });

        return emitter;
    }

    private String escapeJson(String s) {
        if (s == null) return "null";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    /**
     * 循环内上下文裁剪：messageList 估算 token 超预算时，裁剪「固定前缀之后」的步骤消息
     * （nextStepPrompt + 工具结果），保留前缀（历史 + 首条用户任务）不动——这样保存逻辑的
     * historySize 锚点始终有效。工具调用对（AssistantMessage 带 toolCalls + 紧随的
     * ToolResponseMessage）成组保留，避免拆散导致模型无法关联工具结果。
     * budgetService 未注入时无操作。
     */
    @Override
    protected void beforePrompt() {
        if (budgetService == null) {
            return;
        }
        List<Message> messages = getMessageList();
        int budget = budgetService.historyBudget(lockedModel);
        if (budgetService.estimateTokens(messages, lockedModel) <= budget) {
            return;
        }
        int prefixCount = Math.min(pinnedPrefixSize, messages.size());
        List<Message> prefix = new ArrayList<>(messages.subList(0, prefixCount));
        int remaining = budget - budgetService.estimateTokens(prefix, lockedModel);
        List<Message> steps = messages.subList(prefixCount, messages.size());
        List<Message> keptSteps = trimTail(steps, lockedModel, Math.max(remaining, 1));
        List<Message> rebuilt = new ArrayList<>(prefix);
        rebuilt.addAll(keptSteps);
        setMessageList(rebuilt);
        log.info("循环内上下文裁剪: 消息 {} → {}（预算 {} token）",
                messages.size(), rebuilt.size(), budget);
    }

    /** 从尾部保留最近消息，累计 token 不超过预算；工具结果与其前的工具调用消息成对保留。 */
    private List<Message> trimTail(List<Message> messages, String model, int budget) {
        List<Message> kept = new ArrayList<>();
        int tokens = 0;
        for (int i = messages.size() - 1; i >= 0; i--) {
            Message m = messages.get(i);
            if (m instanceof ToolResponseMessage) {
                Message am = (i - 1 >= 0) ? messages.get(i - 1) : null;
                int pairTokens = tokenOf(model, m) + (am != null ? tokenOf(model, am) : 0);
                if (!kept.isEmpty() && tokens + pairTokens > budget) {
                    break;
                }
                if (am != null) {
                    kept.add(0, am);
                    tokens += tokenOf(model, am);
                    i--;
                }
                kept.add(0, m);
                tokens += tokenOf(model, m);
            } else {
                int t = tokenOf(model, m);
                if (!kept.isEmpty() && tokens + t > budget) {
                    break;
                }
                kept.add(0, m);
                tokens += t;
            }
        }
        if (kept.isEmpty() && !messages.isEmpty()) {
            kept.add(messages.get(messages.size() - 1));
        }
        return kept;
    }

    private int tokenOf(String model, Message m) {
        return budgetService.estimateTokens(m, model);
    }

    /** 推送上下文用量命名事件（前端实时填充条）。 */
    private void emitContextUsage(SseEmitter emitter, int step) {
        if (budgetService == null) {
            return;
        }
        try {
            int used = budgetService.estimateTokens(getMessageList(), lockedModel);
            int budget = budgetService.historyBudget(lockedModel);
            double ratio = budget > 0 ? (double) used / budget : 0.0;
            String json = "{\"step\":" + step + ",\"used\":" + used
                    + ",\"budget\":" + budget
                    + ",\"ratio\":" + String.format(java.util.Locale.ROOT, "%.2f", ratio) + "}";
            emitter.send(SseEmitter.event().name("context_usage").data(json));
        } catch (Exception e) {
            log.debug("发送 context_usage 失败: {}", e.getMessage());
        }
    }

    /**
     * 过滤掉内部注入的 nextStepPrompt 用户消息（agent 的思考提示，不是真实对话内容）。
     * 用元数据标记过滤，而非文本比对——死循环调整会改写 nextStepPrompt，文本比对会漏掉早期注入。
     */
    private List<Message> filterInternalPrompts(List<Message> messages) {
        return messages.stream()
                .filter(m -> !(m instanceof org.springframework.ai.chat.messages.UserMessage
                        && Boolean.TRUE.equals(((org.springframework.ai.chat.messages.UserMessage) m)
                                .getMetadata().get(ToolCallAgent.NEXT_STEP_PROMPT_MARKER))))
                .toList();
    }

    /**
     * 把可用工作流清单（名称+描述）追加到 systemPrompt，引导模型自主触发 runWorkflow。
     */
    private void injectWorkflowGuide() {
        if (workflowLoader == null || workflowsInjected) {
            return;
        }
        workflowsInjected = true;
        java.util.List<Workflow> workflowList = workflowLoader.list();
        if (workflowList == null || workflowList.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(getSystemPrompt());
        sb.append("\n\n【可用工作流】\n");
        for (Workflow w : workflowList) {
            sb.append("- ").append(w.getName()).append(": ").append(w.getDescription()).append("\n");
        }
        sb.append("\n当用户任务与某个工作流的标准流程匹配时，调用 runWorkflow(工作流名, 入参JSON) 运行它，不要自行临时发挥。");
        this.setSystemPrompt(sb.toString());
    }

    /**
     * 把可用专家清单（名称+描述）追加到 systemPrompt，引导 supervisor 自主委派。
     */
    private void injectAgentGuide() {
        if (agentLoader == null || agentsInjected) {
            return;
        }
        agentsInjected = true;
        java.util.List<WorkerAgent> agentList = agentLoader.list();
        if (agentList == null || agentList.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(getSystemPrompt());
        sb.append("\n\n【可用专家】\n");
        for (WorkerAgent a : agentList) {
            sb.append("- ").append(a.getName())
                    .append(a.getDescription() == null || a.getDescription().isBlank() ? "" : ": " + a.getDescription())
                    .append("\n");
        }
        sb.append("\n对复杂任务，先拆解，将子任务委派给合适的专家（delegate 工具，可并行），"
                + "最后综合各专家结果给出完整回答。");
        this.setSystemPrompt(sb.toString());
    }

    /**
     * 把可用技能清单（名称+描述）追加到 systemPrompt，引导模型自主触发 useSkill。
     */
    private void injectSkillGuide() {
        if (skillLoader == null || skillsInjected) {
            return;
        }
        skillsInjected = true;
        java.util.List<Skill> skillList = skillLoader.list();
        if (skillList == null || skillList.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder(getSystemPrompt());
        sb.append("\n\n【可用技能】\n");
        for (Skill s : skillList) {
            sb.append("- ").append(s.getName()).append(": ").append(s.getDescription()).append("\n");
        }
        sb.append("\n当用户任务与某个技能匹配时，先调用 useSkill(技能名) 激活，再严格按该技能指南执行。");
        this.setSystemPrompt(sb.toString());
    }

    /**
     * 合并本地工具和 MCP 工具，返回统一的 ToolCallback 数组。
     */
    private static ToolCallback[] mergeTools(ToolCallback[] localTools, ToolCallbackProvider toolCallbacks) {
        ToolCallback[] mcpTools = toolCallbacks.getToolCallbacks();
        ToolCallback[] merged = new ToolCallback[localTools.length + mcpTools.length];
        System.arraycopy(localTools, 0, merged, 0, localTools.length);
        System.arraycopy(mcpTools, 0, merged, localTools.length, mcpTools.length);
        return merged;
    }
}
