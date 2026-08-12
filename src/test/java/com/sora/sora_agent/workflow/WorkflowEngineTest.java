package com.sora.sora_agent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkflowEngine} 单元测试 — 全 Mock，离线可跑。
 */
class WorkflowEngineTest {

    private ChatModel chatModel;
    private WorkflowLoader workflowLoader;
    private ObjectProvider<ToolCallback[]> toolProvider;
    private WorkflowEngine engine;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatModel = mock(ChatModel.class);
        workflowLoader = mock(WorkflowLoader.class);
        toolProvider = mock(ObjectProvider.class);
        engine = new WorkflowEngine(workflowLoader, chatModel, toolProvider, new ObjectMapper());
    }

    private ChatResponse resp(String text) {
        Generation generation = mock(Generation.class);
        when(generation.getOutput()).thenReturn(new AssistantMessage(text));
        ChatResponse response = mock(ChatResponse.class);
        when(response.getResult()).thenReturn(generation);
        return response;
    }

    private ToolCallback tool(String name, String result) {
        ToolCallback cb = mock(ToolCallback.class);
        ToolDefinition td = mock(ToolDefinition.class);
        when(td.name()).thenReturn(name);
        when(cb.getToolDefinition()).thenReturn(td);
        when(cb.call(anyString())).thenReturn(result);
        return cb;
    }

    private Workflow llmWorkflow(String stepId, String prompt) {
        Workflow w = new Workflow();
        w.setName("wf");
        WorkflowStep s = new WorkflowStep();
        s.setId(stepId);
        s.setType("llm");
        s.setPrompt(prompt);
        w.setSteps(List.of(s));
        return w;
    }

    @Test
    void llmStepInterpolatesInputAndRuns() {
        when(workflowLoader.get("wf")).thenReturn(llmWorkflow("step1", "调研主题: {{input.topic}}"));
        when(chatModel.call(any(Prompt.class))).thenReturn(resp("总结结果"));

        String summary = engine.runSync("wf", Map.of("topic", "Spring AI"));

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(captor.capture());
        assertTrue(captor.getValue().getContents().contains("调研主题: Spring AI"));
        assertTrue(summary.contains("总结结果"));
        assertTrue(summary.contains("step1"));
    }

    @Test
    void toolStepInvokesRegisteredTool() {
        Workflow w = new Workflow();
        w.setName("wf");
        WorkflowStep s = new WorkflowStep();
        s.setId("search");
        s.setType("tool");
        s.setTool("searchweb");
        s.setParams(Map.of("query", "{{input.topic}} 进展"));
        w.setSteps(List.of(s));
        when(workflowLoader.get("wf")).thenReturn(w);
        ToolCallback cb = tool("searchweb", "搜索结果...");
        when(toolProvider.getIfAvailable()).thenReturn(new ToolCallback[]{cb});

        String summary = engine.runSync("wf", Map.of("topic", "AI"));

        assertTrue(summary.contains("搜索结果..."));
        org.mockito.ArgumentCaptor<String> captor = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(cb).call(captor.capture());
        assertTrue(captor.getValue().contains("AI 进展"));
    }

    @Test
    void stepResultPassesToNextStep() {
        Workflow w = new Workflow();
        w.setName("wf");
        WorkflowStep s1 = new WorkflowStep();
        s1.setId("s1");
        s1.setType("llm");
        s1.setPrompt("第一步");
        WorkflowStep s2 = new WorkflowStep();
        s2.setId("s2");
        s2.setType("llm");
        s2.setPrompt("上一步结果: {{steps.s1.result}}");
        w.setSteps(List.of(s1, s2));
        when(workflowLoader.get("wf")).thenReturn(w);
        when(chatModel.call(any(Prompt.class))).thenReturn(resp("RESULT1"), resp("FINAL2"));

        String summary = engine.runSync("wf", Map.of());

        org.mockito.ArgumentCaptor<Prompt> captor = org.mockito.ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel, times(2)).call(captor.capture());
        assertEquals("第一步", captor.getAllValues().get(0).getContents());
        assertTrue(captor.getAllValues().get(1).getContents().contains("RESULT1"));
        assertTrue(summary.contains("FINAL2"));
    }

    @Test
    void failsFastOnMissingTool() {
        Workflow w = new Workflow();
        w.setName("wf");
        WorkflowStep s = new WorkflowStep();
        s.setId("s1");
        s.setType("tool");
        s.setTool("not-registered-tool");
        w.setSteps(List.of(s));
        when(workflowLoader.get("wf")).thenReturn(w);
        when(toolProvider.getIfAvailable()).thenReturn(new ToolCallback[]{});

        assertThrows(WorkflowEngine.WorkflowExecutionException.class, () -> engine.runSync("wf", Map.of()));
    }

    @Test
    void failsFastAndStopsOnStepError() {
        Workflow w = new Workflow();
        w.setName("wf");
        WorkflowStep s1 = new WorkflowStep();
        s1.setId("s1");
        s1.setType("llm");
        s1.setPrompt("会失败的步骤");
        WorkflowStep s2 = new WorkflowStep();
        s2.setId("s2");
        s2.setType("llm");
        s2.setPrompt("不应执行的步骤");
        w.setSteps(List.of(s1, s2));
        when(workflowLoader.get("wf")).thenReturn(w);
        when(chatModel.call(any(Prompt.class))).thenThrow(new RuntimeException("模型挂了"));

        assertThrows(WorkflowEngine.WorkflowExecutionException.class, () -> engine.runSync("wf", Map.of()));
        // 第 1 步失败后第 2 步不应执行
        verify(chatModel, times(1)).call(any(Prompt.class));
    }

    @Test
    void unknownWorkflowThrows() {
        when(workflowLoader.get("missing")).thenReturn(null);
        assertThrows(IllegalArgumentException.class, () -> engine.runSync("missing", Map.of()));
    }
}
