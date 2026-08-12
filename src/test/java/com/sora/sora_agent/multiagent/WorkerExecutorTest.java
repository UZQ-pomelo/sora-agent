package com.sora.sora_agent.multiagent;

import com.sora.sora_agent.config.WorkerAgentProperties;
import com.sora.sora_agent.multiagent.WorkerExecutor.Delegation;
import com.sora.sora_agent.multiagent.WorkerExecutor.DelegationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link WorkerExecutor} 单元测试 — 注入替身 {@link WorkerRunner}，离线可跑。
 */
class WorkerExecutorTest {

    private WorkerAgentLoader loader;
    private WorkerRunner runner;
    private WorkerAgentProperties props;
    private WorkerExecutor executor;

    @BeforeEach
    void setUp() {
        loader = mock(WorkerAgentLoader.class);
        runner = mock(WorkerRunner.class);
        props = new WorkerAgentProperties();
        props.setMaxConcurrency(2);
        executor = new WorkerExecutor(loader, props, runner);
    }

    @Test
    void delegatesAllTasksInOrder() {
        WorkerAgent researcher = new WorkerAgent();
        researcher.setName("researcher");
        when(loader.get("researcher")).thenReturn(researcher);
        when(runner.run(any(), eq("调研A"))).thenReturn("结果A");
        when(runner.run(any(), eq("调研B"))).thenReturn("结果B");

        List<DelegationResult> results = executor.delegateParallel(List.of(
                new Delegation("researcher", "调研A"),
                new Delegation("researcher", "调研B")));

        assertEquals(2, results.size());
        assertEquals("结果A", results.get(0).result());
        assertEquals("结果B", results.get(1).result());
        verify(runner, times(2)).run(any(), any());
    }

    @Test
    void unknownWorkerReportsError() {
        when(loader.get("ghost")).thenReturn(null);
        when(loader.names()).thenReturn(List.of("researcher"));

        List<DelegationResult> results =
                executor.delegateParallel(List.of(new Delegation("ghost", "任务")));

        assertEquals(1, results.size());
        assertTrue(results.get(0).result().contains("专家不存在"));
        verify(runner, never()).run(any(), any());
    }

    @Test
    void workerFailureIsIsolated() {
        WorkerAgent a = new WorkerAgent();
        a.setName("a");
        WorkerAgent b = new WorkerAgent();
        b.setName("b");
        when(loader.get("a")).thenReturn(a);
        when(loader.get("b")).thenReturn(b);
        when(runner.run(any(), eq("t1"))).thenReturn("OK1");
        when(runner.run(any(), eq("t2"))).thenThrow(new RuntimeException("boom"));

        List<DelegationResult> results = executor.delegateParallel(List.of(
                new Delegation("a", "t1"),
                new Delegation("b", "t2")));

        assertEquals("OK1", results.get(0).result());
        assertTrue(results.get(1).result().contains("执行失败"));
    }

    @Test
    void emptyReturnsEmpty() {
        assertTrue(executor.delegateParallel(List.of()).isEmpty());
    }
}
