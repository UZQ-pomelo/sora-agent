package com.sora.sora_agent.multiagent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sora.sora_agent.multiagent.WorkerExecutor.Delegation;
import com.sora.sora_agent.multiagent.WorkerExecutor.DelegationResult;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import java.util.ArrayList;
import java.util.List;

/**
 * 并行/串行委派工具（supervisor 自主触发通道）。
 *
 * <p>接受任务数组 JSON：传 1 项 = 串行执行单个 worker，传多项 = 并发执行多个 worker。
 * 全部完成后把各 worker 结果合并返回给 supervisor。</p>
 */
public class DelegateTool {

    private final WorkerExecutor workerExecutor;
    private final ObjectMapper objectMapper;

    public DelegateTool(WorkerExecutor workerExecutor, ObjectMapper objectMapper) {
        this.workerExecutor = workerExecutor;
        this.objectMapper = objectMapper;
    }

    @Tool(description = "委派任务给专家 agent 执行。接受任务数组 JSON，每项含 worker(专家名) 与 task(任务描述)。"
            + "传 1 项=串行执行，传多项=并发执行。专家名单见系统提示中的『可用专家』清单。")
    public String delegate(
            @ToolParam(description = "任务数组 JSON，如 [{\"worker\":\"researcher\",\"task\":\"调研竞品A\"}]") String tasks) {
        try {
            List<Delegation> list = parseTasks(tasks);
            if (list.isEmpty()) {
                return "委派清单为空";
            }
            List<DelegationResult> results = workerExecutor.delegateParallel(list);
            return format(results);
        } catch (Exception e) {
            return "委派失败: " + e.getMessage();
        }
    }

    private List<Delegation> parseTasks(String tasks) throws Exception {
        if (tasks == null || tasks.isBlank()) {
            return List.of();
        }
        JsonNode root = objectMapper.readTree(tasks);
        if (!root.isArray()) {
            throw new IllegalArgumentException("委派清单必须是 JSON 数组");
        }
        List<Delegation> list = new ArrayList<>();
        for (JsonNode node : root) {
            String worker = node.path("worker").asText(null);
            String task = node.path("task").asText(null);
            if (worker == null || worker.isBlank() || task == null || task.isBlank()) {
                throw new IllegalArgumentException("委派项缺少 worker 或 task: " + node);
            }
            list.add(new Delegation(worker, task));
        }
        return list;
    }

    private String format(List<DelegationResult> results) {
        StringBuilder sb = new StringBuilder("专家委派完成：\n");
        for (DelegationResult r : results) {
            sb.append("【").append(r.worker()).append("】任务：「").append(truncate(r.task(), 60)).append("」\n")
                    .append(truncate(r.result(), 600)).append("\n\n");
        }
        return sb.toString();
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
