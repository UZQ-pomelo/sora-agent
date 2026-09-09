/**
 * 生成 UUID。
 *
 * crypto.randomUUID 仅在安全上下文（https 或 localhost）可用；
 * 非安全上下文下降级为 Math.random 生成（无安全强度要求，仅作唯一标识）。
 */
export function uuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  // 降级：模拟 UUID v4
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}
