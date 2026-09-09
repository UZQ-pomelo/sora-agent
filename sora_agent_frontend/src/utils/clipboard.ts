/**
 * 安全地复制文本到剪贴板。
 *
 * navigator.clipboard 仅在安全上下文（https 或 localhost）可用；
 * 非安全上下文下降级为隐藏 textarea + document.execCommand('copy')。
 *
 * @returns 是否复制成功
 */
export async function safeCopy(text: string): Promise<boolean> {
  try {
    if (navigator.clipboard && window.isSecureContext) {
      await navigator.clipboard.writeText(text)
      return true
    }
  } catch {
    // 落到降级路径
  }
  try {
    const textarea = document.createElement('textarea')
    textarea.value = text
    textarea.setAttribute('readonly', '')
    textarea.style.position = 'fixed'
    textarea.style.opacity = '0'
    document.body.appendChild(textarea)
    textarea.select()
    const ok = document.execCommand('copy')
    document.body.removeChild(textarea)
    return ok
  } catch {
    return false
  }
}
