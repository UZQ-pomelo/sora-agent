# 工作流（Workflow）

声明式线性步骤编排：把有固定 SOP 的任务固化成 YAML 模板，由确定性引擎执行。

## 与 ReAct / Skill 的关系

| | 谁执行 | 确定性 |
|---|---|---|
| **ReAct agent** | 模型每步自主决策 | 低（不可预测） |
| **Skill** | 模型按指令自主执行 | 中 |
| **Workflow** | 引擎按固定步骤执行 | 高（可复现） |

工作流**不取代** ReAct，是并存的第二种执行模式：任务有标准流程 → agent 自主调
`runWorkflow`；开放探索任务 → 继续自主 ReAct。

## 核心机制

```
workflows/*.yaml（classpath）+ app.workflow.dir（可扩展）
        │ WorkflowLoader 启动扫描
        ▼
Workflow{ name, description, input, steps[] }   steps: tool | llm
        │
① 工作流清单注入 systemPrompt → agent 知道有哪些工作流
② agent 自主调 runWorkflow(name, 入参JSON) / 前端 /workflow名 / 直接调 /api/ai/workflow/run
        ▼
WorkflowEngine 按线性步骤执行，逐步发 SSE 事件：
  workflow_started → step_started → step_finished → … → workflow_finished
```

## 定义工作流

```yaml
name: research-report
description: 联网调研并生成结构化报告
input:
  - topic
steps:
  - id: search          # 步骤唯一标识
    type: tool          # tool：调已注册工具
    tool: searchweb
    params:
      query: "{{input.topic}} 最新进展"
  - id: summarize
    type: llm           # llm：纯模型调用
    prompt: |
      调研主题：{{input.topic}}
      搜索结果：
      {{steps.search.result}}
      请总结关键要点。
```

### 模板变量

| 写法 | 含义 |
|---|---|
| `{{input.xx}}` | 工作流入参 xx（运行时 JSON 键值） |
| `{{steps.某id.result}}` | 某一步的输出 |

### 步骤类型（首轮）

- `tool`：调一个已注册工具（`params` 值为模板字符串；安全开关未开启的工具不可用）
- `llm`：用 `prompt`（模板插值后）调默认模型，输出文本

**首轮范围**：线性步骤；无嵌套子流程、无分支、无循环。任一步失败即终止（fail-fast）。

## 触发途径

| 途径 | 谁决定 |
|---|---|
| agent 自主 `runWorkflow(name, 入参JSON)` | agent |
| 前端 `/workflow research-report` | 用户 |
| `GET /api/ai/workflow/run?name=xx&input={json}`（SSE） | 调用方 |

## 配置

```yaml
app:
  workflow:
    enabled: true
    dir: ./workflows   # 可选：文件系统工作流目录
```

## 验证

### 自动测试（离线）
```bash
./mvnw test -Dtest="com.sora.sora_agent.workflow.*"
```

### 手工验证
1. `curl -N "http://localhost:8080/api/ai/workflow/run?name=research-report&input=%7B%22topic%22%3A%22Spring%20AI%22%7D"`
   应看到 `workflow_started / step_started / step_finished / workflow_finished` 事件流。
2. `/manus` 页输入 `/workflow research-report` → agent 应调 `runWorkflow` 并按流程执行。
3. 直接提问「帮我调研 XX 并生成报告」→ agent 应自主匹配该工作流并调用。
4. 工作流工具步调用未开启的工具 → 该步报错并终止（fail-fast）。

## 边界

- 工作流清单随 SoraManus 每次请求注入（agent 无状态）。
- 工具步不可调用 `runWorkflow` 自身（防递归）。
- `llm` 步暂用默认模型；热加载预留 `reload()`，当前需重启。
