# 会话管理（上下文管理首轮）

无状态 Agent + 外部记忆：Manus 智能体从"每次请求失忆"升级为"同会话跨请求有记忆"，
并配套前端「对话记录」面板用于查看/切换历史会话。

## 架构

```
前端 Manus 页
  ├─ 发送消息 → /api/ai/manus/chat?message=..&chatId=..&model=..
  │            （带 chatId：后端先载入历史 → 跑 ReAct → 结束后持久化本轮新增）
  ├─ 对话记录按钮 → GET /api/ai/manus/conversations        （会话列表）
  └─ 点击切换会话 → GET /api/ai/manus/conversations/{id}/messages （拉历史渲染）
        + 把 chatId 写入 URL query（/manus?chat=xxx），刷新可恢复

后端
  ConversationMemory（service）
    ├─ load(id)：按命名空间读历史 → 超窗(默认20条)则旧消息 LLM 摘要为 SystemMessage 注入
    ├─ save(id, msgs)：只落 user/assistant 文本（工具调用不落库）
    └─ clear(id)
  ConversationService（service）：会话列表（GROUP BY conversation_id）+ 历史拉取
  MySQLChatMemory（已有 ChatMemory 实现）＝ 存储层（换库只需实现 ChatMemory）
```

## 关键设计

| 项 | 说明 |
|---|---|
| 命名空间 | conversationId 以 `manus:` 前缀落库，与 Tour 助手隔离 |
| 无状态 | 每次请求 `new SoraManus`，启动载入历史、结束持久化；不维护常驻实例池 |
| 只存文本 | 工具调用不进长期记忆；DB 是完整转录，上下文窗口由 load 侧压缩 |
| 排序 | 会话列表按 `MAX(id)`（AUTO_INCREMENT 全局单调）排序，不依赖 create_time |
| 标题 | 每会话首条 user 消息截断（`title-max-length`，默认 30） |

## 新增接口

| 接口 | 说明 |
|---|---|
| `GET /api/ai/manus/chat?message&chatId&model` | `chatId` 可选：带则记忆，不带则无状态 |
| `GET /api/ai/manus/conversations` | 会话列表（命名空间过滤） |
| `GET /api/ai/manus/conversations/{id}/messages` | 某会话历史（role/content 数组） |

> 均在 `/ai/**` 下，已被 API Key 认证 Filter 保护。

## 建表

```bash
# 首次部署执行一次（已存在则跳过）
mysql -u<user> -p <db> < sql/schema.sql
```

`sql/schema.sql` 定义 `chat_memory_message` 表：主键、`conversation_id`、`message_index`、
`message_type`、`message_text`、`create_time(DEFAULT CURRENT_TIMESTAMP)`，
并建 `(conversation_id, message_index)` 复合索引。

## 配置（application-local.yml）

```yaml
app:
  memory:
    namespace: manus           # 会话命名空间
    window-size: 20            # 上下文保留最近消息条数
    summarize-overflow: true   # 超窗是否摘要压缩
    summarize-input-limit: 8000
    title-max-length: 30       # 会话列表标题截断长度
```

## 验证

### 自动测试（离线）
```bash
./mvnw test -Dtest="com.sora.sora_agent.chatmemory.*,com.sora.sora_agent.service.*"
```

### 手工验证（起前后端后）
1. 打开 `/manus`，第一轮问「帮我搜索 Spring AI 最新版本」。
2. 第二轮直接说「把刚才搜到的整理成 PDF」→ agent 应记得上一轮内容（不重复搜索）。
3. 点「对话记录」→ 应出现刚才的会话（标题=首轮消息截断）。
4. 点「新建对话」→ URL 的 `?chat=` 被清空，开启全新会话。
5. 点「对话记录」切回旧会话 → 恢复历史并可继续对话；刷新页面后 URL 仍带 `?chat=`，历史可恢复。
6. 到 `/tour` 页发消息 → 与 Manus 会话互不干扰（命名空间隔离）。

## 边界与后续

- 跨页面刷新保留会话依赖 URL 的 `chat` 参数；浏览器前进/后退到无参数状态不会自动开新会话（保持当前）。
- 摘要模型复用默认 ChatModel；摘要失败安全降级为丢弃超窗消息。
- 后续方向：技能（Skill）、工作流（Workflow）、多 Agent（Multi-agent）按依赖序在记忆之上叠加。
