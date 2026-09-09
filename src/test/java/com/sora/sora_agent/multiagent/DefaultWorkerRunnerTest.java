package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.agent.SoraManus;
import com.sora.sora_agent.config.ModelConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link DefaultWorkerRunner} 单元测试 — 验证 worker 硬约束（工具过滤 / max-steps）生效。
 * 通过 {@code buildWorker} 校验，不实际跑 agent（离线）。
 */
class DefaultWorkerRunnerTest {

    private ChatModel chatModel;
    private ObjectProvider<ToolCallback[]> toolProvider;
    private ModelConfig modelConfig;
    private DefaultWorkerRunner runner;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = mock(ChatModel.class);
        toolProvider = mock(ObjectProvider.class);
        modelConfig = mock(ModelConfig.class);
        when(modelConfig.getDefaultModel()).thenReturn("test-model");
        runner = new DefaultWorkerRunner(chatModel, toolProvider, modelConfig);
    }

    private ToolCallback tool(String name) {
        ToolCallback cb = mock(ToolCallback.class);
        // ToolDefinition.name() 是 final 方法，Mockito 默认 mockmaker 无法 stub → 用真实对象
        when(cb.getToolDefinition()).thenReturn(new DefaultToolDefinition(name, name + " 描述", "{}"));
        return cb;
    }

    @Test
    void workerToolsExcludeForbiddenAndDelegateAndRunWorkflow() {
        // 先构建数组再 stub（tool() 内部有 when()，不能嵌套在 thenReturn 参数里）
        ToolCallback[] tools = new ToolCallback[]{
                tool("searchweb"), tool("webScraping"), tool("executeTerminalCommand"),
                tool("delegate"), tool("runWorkflow"), tool("generatePDF")};
        when(toolProvider.getIfAvailable()).thenReturn(tools);

        WorkerAgent def = new WorkerAgent();
        def.setName("w");
        def.setRole("角色");
        // 白名单里故意包含 delegate，但硬禁令 + 防递归必须把它排除
        def.setTools(List.of("searchweb", "webScraping", "delegate"));
        def.setForbiddenTools(List.of("webScraping"));

        SoraManus worker = runner.buildWorker(def);
        List<String> names = Arrays.stream(worker.getAvailableTools())
                .map(t -> t.getToolDefinition().name())
                .toList();

        // webScraping 被 forbidden 禁、delegate/runWorkflow 被防递归排除
        assertEquals(List.of("searchweb"), names);
    }

    @Test
    void emptyWhitelistDeniesAll() {
        ToolCallback[] tools = new ToolCallback[]{
                tool("searchweb"), tool("executeTerminalCommand"), tool("delegate")};
        when(toolProvider.getIfAvailable()).thenReturn(tools);

        WorkerAgent def = new WorkerAgent();
        def.setName("w");
        def.setRole("角色");
        def.setForbiddenTools(List.of("executeTerminalCommand"));

        SoraManus worker = runner.buildWorker(def);
        List<String> names = Arrays.stream(worker.getAvailableTools())
                .map(t -> t.getToolDefinition().name())
                .toList();

        // 空白名单 = 禁用一切（安全默认：worker 必须显式列出可用工具）
        assertEquals(List.of(), names);
    }

    @Test
    void maxStepsAppliedWhenConfigured() {
        when(toolProvider.getIfAvailable()).thenReturn(new ToolCallback[]{});
        WorkerAgent def = new WorkerAgent();
        def.setName("w");
        def.setRole("角色");
        def.setMaxSteps(5);

        SoraManus worker = runner.buildWorker(def);
        assertEquals(5, worker.getMaxSteps());
    }

    @Test
    void defaultMaxStepsKeptWhenAbsent() {
        when(toolProvider.getIfAvailable()).thenReturn(new ToolCallback[]{});
        WorkerAgent def = new WorkerAgent();
        def.setName("w");
        def.setRole("角色");

        SoraManus worker = runner.buildWorker(def);
        assertEquals(20, worker.getMaxSteps());
    }

    @Test
    void customModelApplied() {
        when(toolProvider.getIfAvailable()).thenReturn(new ToolCallback[]{});
        WorkerAgent def = new WorkerAgent();
        def.setName("w");
        def.setRole("角色");
        def.setModel("custom-model");

        SoraManus worker = runner.buildWorker(def);
        assertEquals("custom-model", worker.getLockedModel());
    }
}
