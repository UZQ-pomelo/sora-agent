# 前端项目设计提示词

## 项目概述

在 `sora_agent_frontend` 目录下创建一个 Vue3 前端项目，包含一个主页和两个 AI 聊天页面。

---

## 技术栈

- **框架**: Vue 3 (Composition API, `<script setup lang="ts">`)
- **语言**: TypeScript
- **构建工具**: Vite
- **路由**: Vue Router 4 (懒加载)
- **样式**: Tailwind CSS (通过 `@tailwindcss/vite` 插件集成)
- **Markdown 渲染**: markdown-it
- **HTTP 客户端**: 不需要 Axios（SSE 用原生 EventSource）
- **状态管理**: 不需要 Pinia，用组件内 `ref`/`reactive`

---

## 路由设计

| 路径 | 页面 | 说明 |
|------|------|------|
| `/` | HomePage | 主页，展示两个应用入口卡片 |
| `/tour` | TourChatPage | AI 旅游助手聊天页 |
| `/manus` | ManusChatPage | AI 智能体聊天页 |

- chatId 不出现在 URL 中，由页面组件内部状态管理
- 使用懒加载

---

## 后端接口

接口前缀: `http://localhost:8080/api/ai`

### 接口 1: 旅游助手 SSE 流式

```
GET /tour_app/chat/sse
参数: message (string, 必填), chatId (string, 可选)
返回: Content-Type: text/event-stream
数据格式: Flux<String>，每个 chunk 是一个纯文本字符串（单词级别流式）
```

### 接口 2: 智能体 SSE 流式

```
GET /manus/chat
参数: message (string, 必填)
注意: 此接口目前不支持 chatId（无状态，每次请求独立）
返回: Content-Type: text/event-stream (SseEmitter)
数据格式: 命名事件流，可能有 error 事件
```

---

## 数据结构

```typescript
interface ChatMessage {
  id: string          // crypto.randomUUID()
  role: 'user' | 'assistant'
  content: string     // 完整文本内容，流式过程中逐步追加
  timestamp: number   // Date.now()
}
```

---

## 组件树

```
src/
├── App.vue                    # 根组件，仅 <router-view>
├── router/
│   └── index.ts               # Vue Router 配置
├── views/
│   ├── HomePage.vue           # 主页：两个应用入口卡片
│   ├── TourChatPage.vue       # 旅游助手聊天页
│   └── ManusChatPage.vue      # 智能体聊天页
├── components/
│   ├── ChatBubble.vue         # 单条聊天气泡（user 右侧 / assistant 左侧）
│   ├── ChatInput.vue          # 底部输入框组件
│   └── ChatContainer.vue      # 聊天核心容器（消息列表 + 输入框 + SSE 逻辑）
└── utils/
    └── sse.ts                 # EventSource 封装工具函数
```

- `ChatContainer.vue` 被 `TourChatPage` 和 `ManusChatPage` 复用
- 两个聊天页面的唯一差异：SSE URL 不同、是否传 chatId 参数

---

## chatId 生成规则

- 进入聊天页面时自动生成: `crypto.randomUUID()`
- 存入组件 `ref`
- 点击"新建对话"按钮时重新生成并清空消息列表
- 旅游助手页面：每次 SSE 请求携带 chatId 参数
- 智能体页面：不携带 chatId（后端暂不支持）

---

## SSE 实现方案（EventSource）

- 使用原生 `EventSource` 消费 SSE
- `utils/sse.ts` 封装连接创建、重连、关闭逻辑
- 流式处理流程：
  1. 用户发送消息 → push `{role: 'user', content: '...'}` 到消息列表
  2. 创建空 `{role: 'assistant', content: ''}` 消息
  3. SSE 每收到 chunk → 追加到 assistant.content
  4. SSE 流结束 → 该条消息完成
  5. `onerror` 时保留默认自动重连 + 消息列表中插入 `{role: 'assistant', content: '⚠️ 连接中断，正在重连...'}` 的系统提示
- 停止生成: 调用 `EventSource.close()`
- 对于 Manus 接口的命名 `error` 事件，通过 `addEventListener('error', handler)` 监听

---

## 核心交互

| 交互 | 行为 |
|------|------|
| 发送消息 | Enter 键发送，Shift+Enter 换行 |
| 空消息 | 不允许发送（按钮置灰 / 输入框为空时无反应） |
| 新消息到达 | 消息列表自动滚动到最底部 |
| 加载状态 | AI 思考时，最后一条 assistant 气泡显示闪烁光标或三点跳动动画 |
| 正在接收流式响应时 | 禁用发送按钮，防止重复提交 |
| 新建对话 | 按钮在聊天页顶部，点击重新生成 chatId + 清空消息列表 |
| 停止生成 | 流式输出过程中可点击停止，关闭 EventSource |
| 复制消息 | 每条 assistant 气泡旁有复制按钮，复制整条消息 |
| 代码块复制 | Markdown 渲染的代码块右上角有复制按钮 |

---

## 视觉设计

- **主题**: 浅色主题，中性灰白简约风
- **目标**: 仅桌面端（不要求移动端适配）
- **主页**: 居中显示两张卡片，分别代表旅游助手和智能体，点击进入对应聊天页
- **聊天页**: 左侧 AI 气泡（白底灰边框），右侧用户气泡（蓝底白字），类似微信/iMessage 风格
- **输入区域**: 底部固定，输入框 + 发送/停止按钮
- **Markdown 渲染**: assistant 气泡中使用 markdown-it 渲染，代码块需有浅灰背景 + 等宽字体

---

## 依赖清单

```json
{
  "dependencies": {
    "vue": "^3.5",
    "vue-router": "^4.5",
    "markdown-it": "^14.1"
  },
  "devDependencies": {
    "@vitejs/plugin-vue": "^5.2",
    "typescript": "~5.7",
    "vite": "^6.3",
    "vue-tsc": "^2.2",
    "@tailwindcss/vite": "^4.1",
    "tailwindcss": "^4.1"
  }
}
```
