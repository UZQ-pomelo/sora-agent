export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

/** Agent 终止状态枚举，与后端 AgentState 对应 */
export type AgentState = 'IDLE' | 'RUNNING' | 'FINISHED' | 'ERROR' | 'STUCK'

export interface AgentStateEvent {
  state: AgentState
}

export type SSEEventType = 'message' | 'error' | 'open' | 'close'

export interface SSEOptions {
  url: string
  onMessage: (chunk: string) => void
  onError?: (error: string) => void
  onComplete?: () => void
  onOpen?: () => void
  /** 收到后端发送的 agent_state 命名事件时触发，用于区分正常完成/死循环终止/错误 */
  onAgentState?: (state: AgentState) => void
}
