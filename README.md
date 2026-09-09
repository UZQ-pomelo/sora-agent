# Sora Agent

基于 Spring AI Alibaba 的通用 AI Agent 服务端 + Vue 3 前端。核心是 ReAct 推理循环，叠加可声明的 Skill / Workflow / Multi-agent 能力体系、工具编排、模型自动降级与按 token 预算管理的会话记忆。

## 技术栈

| 层 | 技术 |
|------|------|
| 后端 | Spring Boot 3.5、Spring AI / Spring AI Alibaba 1.1.2（DashScope） |
| 语言 | Java 21（虚拟线程）、Maven 3.9+ |
| 数据 | PostgreSQL（对话记忆 + pgvector 向量检索）、MyBatis-Plus |
| 模型 | 阿里云百炼 DashScope（deepseek / qwen 多模型，可配降级链） |
| 前端 | Vue 3、TypeScript、Tailwind CSS、Vite |
| 工具 | Exa Search、Jsoup、iText、Spring AI MCP Client |
| 文档 | Knife4j / OpenAPI 3 |

## 特性

- **ReAct 推理引擎** — think → act → step 循环，Agent 自主决定何时调用工具、何时给出最终回答。配三重死循环检测（文本重复 / 连续同工具 / 工具振荡模式），自动注入提示引导跳出，超限强制 STUCK 终止。
- **三大声明式能力** — 技能（Skill）、工作流（Workflow）、多 Agent 编排（Multi-agent），均通过声明式 YAML 定义、由工具结果通道驱动，零引擎改动即可扩展。见「能力体系」。
- **工具系统** — 7 个本地工具（网页搜索 / 内容抓取 / 文件读写 / 资源下载 / 终端操作 / PDF 生成 / 任务终止）+ 可选 MCP 远程工具。危险工具默认关闭，按 `app.security.tools.*` 显式开启。
- **模型自动降级** — 按配置顺序 fallback；按 HTTP 状态码区分致命（401/403）与可恢复错误，致命错误直接中断不降级。SSE `model_info` 事件告知前端实际命中模型。
- **会话记忆 + 上下文管理** — 跨请求持久化（PostgreSQL），写侧裁剪 + 摘要落库防 DB 无限增长；上下文按 **token 预算**（非消息条数）管理，字符/token 比例与固定开销随真实 `usage` 反馈自适应标定。见「会话记忆与上下文管理」。
- **RAG** — pgvector 向量检索 + Query Rewrite + 关键词元数据增强，多文档分批写入。
- **可观测性** — Spring Boot Actuator + Micrometer LLM 业务指标（调用量 / 失败 / 耗时）。
- **前端** — 模型选择、流式输出（Markdown 渲染）、对话记录面板、实时上下文用量条。

## 架构

```
sora_agent_frontend (Vue 3, Vite)
      │  HTTP / SSE（/api，经 X-API-Key 认证，vite dev 代理注入）
      ▼
AiController / ChatController / ImageController        ← Spring Boot, context-path=/api
      │
      ├── Agent 层      SoraManus（Manus 智能体，ReAct+工具） / TourApp（小途示例，RAG+对话）
      │                    ├── Skill（UseSkillTool）  Workflow（RunWorkflowTool）  Multi-agent（DelegateTool）
      │                    └── ToolCallback[]（本地工具 + MCP）
      ├── 服务层        ModelFallbackService（降级链）  ConversationService（会话列表/历史）
      ├── 记忆/上下文   ConversationMemory / PgChatMemory / ContextBudgetService
      └── RAG           pgvector + QueryRewriter
                │
                ▼
          ChatModel（DashScope，包 LLM 并发信号量 + Micrometer 指标 + usage 标定）
                │
                ▼
          PostgreSQL（chat_memory_message 表 + pgvector 向量表）
```

数据流：每步 `think()` 用完整消息上下文 + 工具定义拼 Prompt 调 LLM；调工具后结果回写上下文；每步结束推送进度与 `context_usage` 事件；任务结束按 token 预算写侧裁剪后落库。

## 快速开始

### 前置

| 依赖 | 版本 |
|------|------|
| JDK | 21+（运行时依赖虚拟线程） |
| Maven | 3.9+ |
| Node.js | 18+ |
| PostgreSQL | 16 + pgvector 扩展 |
| DashScope API Key | [阿里云百炼](https://bailian.console.aliyun.com/) |

### 配置

从模板创建本地配置，填入密钥与连接串（两者均已 gitignore）：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
cp src/main/resources/mcp-servers.json.example      src/main/resources/mcp-servers.json

cp sora_agent_frontend/.env.example sora_agent_frontend/.env   # 填 SORA_API_KEY
```

`application-local.yml` 是全部可调项的权威模板（`app.*` 节点注释齐全），至少需要：

- `spring.ai.dashscope.api-key` — DashScope 模型密钥
- `spring.datasource.url/username/password` — PostgreSQL 连接（含 `chat_memory` 表与向量表）
- `app.security.api-keys` — 至少一个 `X-API-Key`。**未配置则应用拒绝启动**（fail-fast，防无鉴权裸奔）。开发时由前端 vite 代理注入该头；生产由反向代理统一注入。密钥必须用非 `VITE_` 前缀命名（如 `SORA_API_KEY`），避免被 `import.meta.env` 暴露进浏览器。

### 启动数据库

```bash
docker run -d --name sora-pg -p 5432:5432 \
  -e POSTGRES_USER=<user> -e POSTGRES_PASSWORD=<pass> -e POSTGRES_DB=sora_agent \
  pgvector/pgvector:pg16
```

首次建表可执行 `sql/schema.sql`。

### 启动

```bash
# 后端（默认 http://localhost:8080，context-path /api）
mvn spring-boot:run

# 前端（新终端，默认 http://localhost:5173，/api 代理到 8080）
cd sora_agent_frontend
npm install
npm run dev
```

默认模型为 `deepseek-v3.2`，可切换到 `qwen-turbo` / `qwen-plus` / `deepseek-v3`（前端下拉或请求参数 `model`）。

## 配置摘要

以下均为 `application-local.yml` 中 `app.*` 节点的可调项：

| 节点 | 作用 |
|------|------|
| `app.models.available[]` | 可用模型：`name` / `display` / `context-tokens`（上下文窗口，决定 token 预算） |
| `app.models.default-model` | 默认模型（亦是降级链首项） |
| `app.security.api-keys` | 合法 `X-API-Key` 列表，按 key 派生租户命名空间隔离会话 |
| `app.security.protect-patterns` | 需认证的路径；图片代理 `/image/**` 等受信中继默认豁免 |
| `app.security.tools.*` | 危险工具（终端 / 文件 / 下载 / 抓取）开关，默认关闭 |
| `app.security.enable-mcp-tools` | 是否合并 MCP 工具，默认 false |
| `app.executor.llm-max-concurrency` | 全局最大并发 LLM 调用数（默认 16，防打爆 DashScope 额度） |
| `app.memory.*` | 会话记忆：命名空间、token 预算与压缩水位、标定种子、标题截断等 |

## 能力体系

三种能力都声明在 YAML 中，Agent 侧通过对应工具按名称触发，扩展能力不碰引擎代码。

| 能力 | 定义目录（classpath） | 触发工具 | 内置样例 | 文档 |
|------|------|------|------|------|
| Skill | `skills/*.yaml` | `useSkill` | `web-researcher` | [skill-system.md](docs/skill-system.md) |
| Workflow | `workflows/*.yaml` | `runWorkflow` | `research-report` | [workflow.md](docs/workflow.md) |
| Multi-agent | `agents/*.yaml` | `delegate` | `analyst`、`researcher` | [multi-agent.md](docs/multi-agent.md) |

Agent 启动时把可用清单注入 system prompt，由模型判断何时调用对应工具；外部目录可通过 `app.executor.dir` 等配置额外加载（详见 `application-local.yml.example` 注释）。

## 会话记忆与上下文管理

对话跨请求持久化在 PostgreSQL（`chat_memory_message` 表，按 `tenant:namespace:conversationId` 隔离）。核心是**按 token 预算**管理，而非固定消息条数：

```
历史预算(model) = contextTokens(model) × (1 − outputReserveRatio) − 固定开销(model)
估算 tokens     = 字符数 ÷ charPerToken(model)
```

- `contextTokens` 在 `app.models.available[].context-tokens` 声明；`outputReserveRatio` 默认 25%，为回复留输出空间。
- 固定开销（system prompt + 工具定义）与字符/token 比例都是按模型的运行态估计，从每次 LLM 响应的真实 `usage.promptTokens` 反推、滚动收敛（`ContextBudgetService`）。比例种子默认 2.5 字符/token，覆盖中英混排冷启动。
- 读侧：载入历史即按预算裁剪（首条标题 + 摘要 + 最近窗口）；写侧：落库后若超高水位（默认 90%）触发压缩到低水位（60%），溢出部分压缩成 `【会话摘要】` 落库或直接丢弃。
- Agent 循环内同样按预算裁剪步骤消息（工具结果/nextStepPrompt 超限时丢旧留新，工具调用与结果成对保留），防止单次任务撑爆窗口。

前端感知：每步结束收到 SSE `context_usage` 事件（`{used, budget, ratio}`）画实时用量条；会话列表接口返回每会话 `tokens / tokensBudget` 画存量条。

详细设计见 [conversation-management.md](docs/conversation-management.md)。

## 示例应用：小途旅行助手

`TourApp` 是框架内置的垂直示例：多轮行程规划 + RAG 知识库（`src/main/resources/document/` 三篇旅行文档）+ 结构化输出 + PDF 导出。用于演示如何基于本框架组装一个带记忆和知识库的助手。

## API

统一前缀 `/api`，请求头携带 `X-API-Key`。交互式文档：启动后访问 `/api/swagger-ui.html`。

| 方法 | 路径 | 说明 |
|------|------|------|
| GET | `/api/ai/models` | 可用模型列表 |
| GET | `/api/ai/manus/chat?message=&chatId=&model=` | Manus 智能体 SSE 流式对话（chatId 启用记忆） |
| GET | `/api/ai/manus/conversations` | 会话列表（含 token 用量） |
| GET | `/api/ai/manus/conversations/{id}/messages` | 会话历史 |
| GET | `/api/ai/workflow/run?name=&input=` | 直接运行工作流（SSE） |
| GET | `/api/ai/tour_app/chat/sse` 等 | 小途助手流式/同步对话 |
| POST | `/api/chat?message=&chatId=` | 直接对话（Knife4j 调试用，同步返回） |
| GET | `/api/image/proxy` | 图片代理（受信中继，绕过浏览器防盗链） |

SSE 命名事件：`model_info`（实际命中模型 / 降级）、`context_usage`（token 用量）、`agent_state`（终止状态）、`error`。

## 安全模型

- **认证**：`X-API-Key` 头校验，fail-fast；按 key 哈希派生租户命名空间，会话/记忆相互隔离；限流防滥用。
- **工具风控**：SSRF 防护（UrlSafety 封内网/IPv6 过渡地址/回环）、路径穿越防护（PathSafety 校验符号链接越界）、危险工具默认关闭、终端命令白名单（CommandGuard）。
- **输出安全**：图片代理内容类型白名单（仅栅格图）+ `nosniff`；响应安全头（CSP、X-Frame-Options: DENY 等）。
- 完整清单与验收见 [security-checklist.md](docs/security-checklist.md)。

## 测试

```bash
mvn test
```

约 100 个离线单元测试，无外部依赖（数据库 / 真实 LLM / MCP 均不参与）。上下文冒烟测试（`@Tag("integration")`）默认被 surefire 排除，需本地配齐环境后手动按标签运行。

## 目录结构

```
src/main/java/com/sora/sora_agent/
├── agent/        BaseAgent / ReActAgent / ToolCallAgent / SoraManus（Agent 继承链 + 状态机）
├── skill/        Skill 体系（Skill / SkillLoader / UseSkillTool）
├── workflow/     Workflow 体系（Workflow / WorkflowEngine / RunWorkflowTool）
├── multiagent/   Multi-agent 体系（WorkerAgent / WorkerExecutor / DelegateTool）
├── chatmemory/   会话记忆与上下文（PgChatMemory / ConversationMemory / ContextBudgetService）
├── rag/          RAG（QueryRewriter、pgvector 配置、文档加载）
├── security/     ApiKeyAuthFilter / SecurityProperties / UrlSafety / PathSafety / CommandGuard
├── config/       模型列表、记忆参数、线程池、并发信号量、usage 标定等
├── controller/   AiController / ChatController / ImageController
├── service/      ModelFallbackService / ConversationService
├── tool/         7 个本地工具 + 注册配置
├── app/          TourApp（小途示例）
├── advisor/      LLM 调用日志 advisor
└── common/ exception/ mapper/ model/   通用返回、全局异常、ORM、实体/DTO

src/main/resources/
├── skills/ workflows/ agents/   声明式能力（内置样例）
├── document/                    小途知识库（RAG 源文档）
└── application-local.yml.example   全量配置模板

sora_agent_frontend/src/
├── views/        HomePage / ManusChatPage / TourChatPage
├── components/   ChatContainer / ChatBubble / ChatInput / ConversationListPanel
├── utils/        sse.ts（SSE 解析）、uuid、clipboard
└── types/        chat.ts
```

## 文档

| 主题 | 路径 |
|------|------|
| 安全加固清单 | [docs/security-checklist.md](docs/security-checklist.md) |
| 会话记忆与 token 上下文管理 | [docs/conversation-management.md](docs/conversation-management.md) |
| 技能体系 | [docs/skill-system.md](docs/skill-system.md) |
| 工作流体系 | [docs/workflow.md](docs/workflow.md) |
| 多 Agent 编排 | [docs/multi-agent.md](docs/multi-agent.md) |

## License

[MIT](LICENSE)
