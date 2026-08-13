/**
 * SSE (Server-Sent Events) 工具
 *
 * 使用 fetch + ReadableStream 手动解析 SSE 流。
 * 比原生 EventSource 更灵活，能处理各种后端 SSE 实现格式。
 */

import type { SSEOptions } from '@/types/chat'

export function createSSEConnection(opts: SSEOptions): { abort: () => void } {
  const { url, onMessage, onError, onComplete, onOpen, onAgentState, onModelInfo } = opts

  const controller = new AbortController()
  let aborted = false

  async function connect() {
    try {
      const response = await fetch(url, {
        headers: { Accept: 'text/event-stream' },
        signal: controller.signal,
      })

      if (!response.ok) {
        onError?.(`⚠️ 请求失败: HTTP ${response.status}`)
        onComplete?.()
        return
      }

      onOpen?.()

      const reader = response.body?.getReader()
      if (!reader) {
        onError?.('⚠️ 浏览器不支持流式读取')
        onComplete?.()
        return
      }

      const decoder = new TextDecoder()
      let buffer = ''

      while (true) {
        const { done, value } = await readWithTimeout(reader)

        if (done) {
          // 流自然结束
          onComplete?.()
          return
        }

        buffer += decoder.decode(value, { stream: true })

        // 按双换行分割 SSE 事件
        const parts = buffer.split('\n\n')
        // 最后一部分可能不完整，保留到下次处理
        buffer = parts.pop() || ''

        for (const part of parts) {
          if (!part.trim()) continue

          // 解析 SSE 事件字段
          const lines = part.split('\n')
          let dataLines: string[] = []
          let eventType = ''

          for (const line of lines) {
            if (line.startsWith('data:')) {
              dataLines.push(line.slice(5).replace(/^ /, ''))
            } else if (line.startsWith('event:')) {
              eventType = line.slice(6).trim()
            } else if (line.startsWith('id:')) {
              // ignore id
            } else if (line.startsWith('retry:')) {
              // ignore retry
            } else if (line.trim() === '') {
              // empty line, skip
            }
          }

          const data = dataLines.join('\n')

          if (!data) continue

          // 处理 agent_state 命名事件（后端 Agent 终止状态）
          if (eventType === 'agent_state') {
            try {
              const parsed = JSON.parse(data)
              if (parsed.state) {
                onAgentState?.(parsed.state)
              }
            } catch {
              // 解析失败则忽略，不影响主流程
            }
            continue
          }

          // 处理 model_info 命名事件（后端模型切换信息）
          if (eventType === 'model_info') {
            try {
              const parsed = JSON.parse(data)
              if (parsed.model !== undefined) {
                onModelInfo?.(parsed)
              }
            } catch {
              // 解析失败则忽略
            }
            continue
          }

          // 处理命名事件
          if (eventType === 'error') {
            onError?.(`⚠️ ${data}`)
            // 业务错误 → 停止解析
            aborted = true
            controller.abort()
            onComplete?.()
            return
          }

          // 处理 [DONE] 标记
          if (data === '[DONE]') {
            aborted = true
            controller.abort()
            onComplete?.()
            return
          }

          // 正常的消息数据
          onMessage(data)
        }
      }
    } catch (err: unknown) {
      if (aborted) return
      if (err instanceof DOMException && err.name === 'AbortError') return
      // 关闭底层连接（空闲超时/异常路径），避免 fetch 流悬挂泄漏
      controller.abort()
      onError?.(`⚠️ 连接失败: ${err instanceof Error ? err.message : '未知错误'}`)
      onComplete?.()
    }
  }

  // 启动连接（不等待 Promise，让它在后台运行）
  connect()

  return {
    abort: () => {
      aborted = true
      controller.abort()
    },
  }
}

/** 空闲超时：超过该时长无数据视为断连，防止 reader.read() 永久 pending 卡死 isStreaming */
const IDLE_TIMEOUT_MS = 60000

function readWithTimeout(
  reader: ReadableStreamDefaultReader<Uint8Array>,
): Promise<{ done: boolean; value?: Uint8Array }> {
  return new Promise((resolve, reject) => {
    let timer: ReturnType<typeof setTimeout>
    const timeout = new Promise<never>((_, rejectTimeout) => {
      timer = setTimeout(() => rejectTimeout(new Error('SSE 连接空闲超时（60s 无数据）')), IDLE_TIMEOUT_MS)
    })
    Promise.race([reader.read(), timeout]).then(
      (v) => {
        clearTimeout(timer!)
        resolve(v)
      },
      (e) => {
        clearTimeout(timer!)
        reject(e)
      },
    )
  })
}
