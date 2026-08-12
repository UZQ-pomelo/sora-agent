# 多 Agent（Multi-agent）

监督者-工人（Supervisor-worker）编排：一个 supervisor agent 拆解任务、把子任务**并行/串行**
委派给各领域专家 worker，收集结果后综合回答。

## 核心机制

```
agents/*.yaml（classpath）+ app.agent.dir（可扩展）
        │ WorkerAgentLoader 启动扫描
        ▼
WorkerAgent{ name, description, role(系统提示), model(可选), tools(白名单) }
        │
① 专家清单注入 supervisor 的 systemPrompt → supervisor 知道有哪些专家
② supervisor 调 delegate(任务数组JSON) → WorkerExecutor 并行/串行跑多个 worker
        ▼
每个 worker = 一个独立 SoraManus 实例（角色提示 + 过滤后的工具 + 可选模型）
  并发执行各自的 ReAct 循环 → 全部完成后结果合并返回给 supervisor
```

## 关键设计

| 项 | 说明 |
|---|---|
| 委派工具 | `delegate([{worker, task}, ...])`：传 1 项=串行，传多项=**并发**执行 |
| 并行实现 | 线程池（`app.agent.max-concurrency`，默认 4），`CompletableFuture` 全等收集 |
| 单 worker 隔离 | 单个 worker 失败返回错误文本，不影响整批 |
| 防递归 | worker 工具集排除 `delegate` 与 `runWorkflow`（worker 不能再委派） |
| worker 工具 | `tools` 白名单从已注册工具过滤；空 = 全部可用（除禁调） |
| **工具硬禁令** | `forbidden-tools`：即使工具已注册也强制排除（优先级高于白名单） |
| **步数硬限制** | `max-steps`：worker 最大步骤数（默认框架 20），防止失控 |
| 模型 | `model` 可选，默认全局默认模型 |

## 定义专家

```yaml
name: researcher
description: 联网调研专家
role: |
  你是一位专业的联网调研专家。你的职责：...
tools:
  - searchweb
  - webScraping
forbidden-tools:        # 硬禁令：这些工具即使注册了也禁用
  - executeTerminalCommand
max-steps: 10           # 硬限制：最多执行 10 步
model: deepseek-v4-flash # 可选
```

## 执行示意

```
用户：帮我做一份竞品分析报告
  ↓
supervisor（SoraManus）
  拆解 → 一次委派 3 个任务（delegate，3 个 worker 并发跑）
    researcher(调研竞品A)  ──┐
    researcher(调研竞品B)  ──┤ 并发
    analyst(分析C)         ──┘
  全部完成后，supervisor 综合结果输出最终报告
```

依赖任务则分多轮委派（每次 1 项，拿结果再委派下一步）。

## 配置

```yaml
app:
  agent:
    enabled: true
    max-concurrency: 4
    dir: ./agents   # 可选：文件系统专家目录
```

## 验证

### 自动测试（离线）
```bash
./mvnw test -Dtest="com.sora.sora_agent.multiagent.*"
```

### 手工验证
1. `/manus` 页输入「帮我调研 A 和 B 并做竞品分析」→ supervisor 应拆解并 `delegate`
   多个任务，SSE 流里可看到专家执行。
2. 专家结果应出现在 supervisor 的最终回答中。
3. `/skill`、`/workflow`、专家委派可组合使用。

## 边界

- 并行 worker 各自独立上下文，不共享对话记忆。
- 首轮 supervisor 只能委派给顶层专家（worker 不可再委派），深度限制为 1 级。
- 热加载预留 `reload()`，当前需重启。
