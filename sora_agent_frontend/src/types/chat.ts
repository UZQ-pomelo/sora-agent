export interface ChatMessage {
  id: string
  role: 'user' | 'assistant'
  content: string
  timestamp: number
}

export type SSEEventType = 'message' | 'error' | 'open' | 'close'

export interface SSEOptions {
  url: string
  onMessage: (chunk: string) => void
  onError?: (error: string) => void
  onComplete?: () => void
  onOpen?: () => void
}
