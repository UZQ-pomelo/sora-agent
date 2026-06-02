/**
 * SSE (Server-Sent Events) 工具
 *
 * 使用 fetch + ReadableStream 手动解析 SSE 流。
 * 比原生 EventSource 更灵活，能处理各种后端 SSE 实现格式。
 */

import type { SSEOptions } from '@/types/chat'

export function createSSEConnection(opts: SSEOptions): { abort: () => void } {
  const { url, onMessage, onError, onComplete, onOpen } = opts

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
        const { done, value } = await reader.read()

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
