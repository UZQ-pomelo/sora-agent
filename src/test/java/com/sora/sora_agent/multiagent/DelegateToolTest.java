package com.sora.sora_agent.multiagent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.multiagent.WorkerExecutor.Delegation;
import com.sora.sora_agent.multiagent.WorkerExecutor.DelegationResult;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link DelegateTool} 单元测试 — mock WorkerExecutor，验证 JSON 解析与结果格式化。
 */
class DelegateToolTest {

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void parsesTaskArrayAndDelegates() throws Exception {
        WorkerExecutor executor = mock(WorkerExecutor.class);
        DelegateTool tool = new DelegateTool(executor, new ObjectMapper());
        when(executor.delegateParallel(anyList())).thenReturn(List.of(
                new DelegationResult("researcher", "调研A", "结果A")));

        String out = tool.delegate("[{\"worker\":\"researcher\",\"task\":\"调研A\"}]");

        assertTrue(out.contains("结果A"));
        ArgumentCaptor<List> captor = ArgumentCaptor.forClass(List.class);
        verify(executor).delegateParallel(captor.capture());
        List<Delegation> parsed = (List<Delegation>) (List<?>) captor.getValue();
        assertEquals(1, parsed.size());
        assertEquals("researcher", parsed.get(0).worker());
        assertEquals("调研A", parsed.get(0).task());
    }

    @Test
    void invalidJsonReturnsFailure() {
        WorkerExecutor executor = mock(WorkerExecutor.class);
        DelegateTool tool = new DelegateTool(executor, new ObjectMapper());
        String out = tool.delegate("not-json");
        assertTrue(out.startsWith("委派失败"));
    }

    @Test
    void emptyListReturnsEmptyMessage() {
        WorkerExecutor executor = mock(WorkerExecutor.class);
        DelegateTool tool = new DelegateTool(executor, new ObjectMapper());
        String out = tool.delegate("[]");
        assertTrue(out.contains("委派清单为空"));
    }
}
