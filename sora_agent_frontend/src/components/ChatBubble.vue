<script setup lang="ts">
import { computed, ref } from 'vue'
import MarkdownIt from 'markdown-it'
import type { ChatMessage, AgentState, ModelInfo } from '@/types/chat'
import { safeCopy } from '@/utils/clipboard'

const props = defineProps<{
  message: ChatMessage
  isStreaming: boolean
  agentState?: AgentState | null
  /** 模型切换信息（收到后端 model_info 事件后传入） */
  modelInfo?: ModelInfo | null
}>()

const emit = defineEmits<{
  (e: 'copy', content: string): void
}>()

const copied = ref(false)
const copiedCode = ref<string | null>(null)

const md = new MarkdownIt({
  html: false,
  linkify: true,
  typographer: true,
  breaks: true,
})

// 安全加固：linkify 生成的链接强制新标签打开并禁止 opener 窃取（反向 tabnabbing）
md.renderer.rules.link_open = (tokens, idx, _options, _env, self) => {
  tokens[idx].attrSet('target', '_blank')
  tokens[idx].attrSet('rel', 'noopener noreferrer')
  return self.renderToken(tokens, idx, _options)
}

const renderedContent = computed(() => {
  if (props.message.role === 'user') {
    return props.message.content
  }
  return md.render(props.message.content)
})

const isUser = computed(() => props.message.role === 'user')
const hasContent = computed(() => props.message.content.length > 0)

const statusLabel = computed(() => {
  if (!props.agentState || props.isStreaming) return null
  switch (props.agentState) {
    case 'FINISHED':
      return { icon: '✅', text: '任务完成', color: 'text-emerald-600 bg-emerald-50 border-emerald-200' }
    case 'STUCK':
      return { icon: '⚠️', text: '任务因检测到死循环而终止', color: 'text-amber-600 bg-amber-50 border-amber-200' }
    case 'ERROR':
      return { icon: '❌', text: '执行出错', color: 'text-red-600 bg-red-50 border-red-200' }
    default:
      return null
  }
})

const modelLabel = computed(() => {
  if (!props.modelInfo) return null
  const info = props.modelInfo
  const displayName = info.model || '未知模型'
  if (info.fallback && info.fallbackFrom) {
    return {
      icon: '🔄',
      text: `已切换至 ${displayName}（原 ${info.fallbackFrom}: ${info.fallbackReason || '调用失败'}）`,
      color: 'text-amber-600 bg-amber-50 border-amber-200',
    }
  }
  // 正常 — 显示当前使用的模型
  return {
    icon: '🧠',
    text: `模型: ${displayName}`,
    color: 'text-slate-500 bg-slate-50 border-slate-200',
  }
})

function onCopy() {
  emit('copy', props.message.content)
  copied.value = true
  setTimeout(() => {
    copied.value = false
  }, 2000)
}

async function onCopyCode(code: string) {
  await safeCopy(code)
  copiedCode.value = code
  setTimeout(() => {
    copiedCode.value = null
  }, 2000)
}

/**
 * Inject copy buttons into code blocks after render.
 * We use a small deferred hack: wrap <pre> blocks in a container
 * and attach click handlers via event delegation.
 */
function processCodeBlocks(el: HTMLElement | null) {
  if (!el) return
  const pres = el.querySelectorAll('pre')
  pres.forEach((pre) => {
    if (pre.closest('.code-block-wrapper')) return
    const wrapper = document.createElement('div')
    wrapper.className = 'code-block-wrapper'
    const btn = document.createElement('button')
    btn.className = 'code-copy-btn'
    btn.textContent = '复制'
    btn.addEventListener('click', () => {
      const code = pre.querySelector('code')?.textContent || pre.textContent || ''
      onCopyCode(code)
    })
    pre.parentNode?.insertBefore(wrapper, pre)
    wrapper.appendChild(pre)
    wrapper.appendChild(btn)
  })
}

function onBubbleRef(el: unknown) {
  if (el instanceof HTMLElement && !isUser.value) {
    processCodeBlocks(el)
  }
}

const formattedTime = computed(() => {
  const d = new Date(props.message.timestamp)
  return d.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
})
</script>

<template>
  <div
    :class="[
      'flex gap-3 mb-5 group',
      isUser ? 'justify-end' : 'justify-start',
    ]"
  >
    <!-- AI avatar (left side only) -->
    <div v-if="!isUser" class="shrink-0 mt-0.5">
      <div
        class="w-8 h-8 rounded-full flex items-center justify-center text-sm font-medium shadow-sm"
        :class="isStreaming && !hasContent
          ? 'bg-accent-100 text-accent-600'
          : 'bg-accent-200 text-accent-700'"
      >
        <span v-if="!isStreaming || hasContent">AI</span>
        <span v-else class="dot-pulse">
          <span></span><span></span><span></span>
        </span>
      </div>
    </div>

    <!-- Bubble -->
    <div class="max-w-[75%] min-w-0">
      <!-- Header: name + time -->
      <div
        :class="[
          'flex items-center gap-2 mb-1 text-xs',
          isUser ? 'justify-end text-warm-400' : 'justify-start text-warm-400',
        ]"
      >
        <span class="font-medium text-warm-500">
          {{ isUser ? 'You' : 'Assistant' }}
        </span>
        <span>{{ formattedTime }}</span>
      </div>

      <!-- Bubble body -->
      <div
        :ref="onBubbleRef"
        :class="[
          'relative rounded-2xl px-4 py-3 text-sm leading-relaxed',
          isUser
            ? 'bg-accent-600 text-white rounded-br-md shadow-sm shadow-accent-200/40'
            : 'bg-white border border-warm-200 rounded-bl-md shadow-sm hover:shadow-md transition-shadow duration-200',
          isStreaming && !hasContent ? 'min-h-[2.5rem]' : '',
        ]"
      >
        <!-- User: plain text -->
        <template v-if="isUser">
          <p class="whitespace-pre-wrap break-words">{{ message.content }}</p>
        </template>

        <!-- Assistant: rendered markdown -->
        <template v-else>
          <div
            v-if="hasContent"
            class="markdown-body"
            v-html="renderedContent"
          ></div>
          <div v-else class="flex items-center h-6">
            <span class="dot-pulse">
              <span></span><span></span><span></span>
            </span>
          </div>
          <!-- Streaming cursor -->
          <span v-if="isStreaming && hasContent" class="typing-cursor"></span>
        </template>

        <!-- Copy button (assistant only, appears on hover) -->
        <button
          v-if="!isUser && hasContent"
          class="absolute -bottom-7 right-1 flex items-center gap-1 text-xs
                 text-warm-400 hover:text-accent-600 opacity-0 group-hover:opacity-100
                 transition-all duration-150 py-0.5 px-1.5 rounded-md hover:bg-accent-50"
          @click="onCopy"
        >
          <svg v-if="!copied" class="w-3.5 h-3.5" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2">
            <rect x="9" y="9" width="13" height="13" rx="2" ry="2"/>
            <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>
          </svg>
          <svg v-else class="w-3.5 h-3.5 text-emerald-600" fill="none" viewBox="0 0 24 24" stroke="currentColor" stroke-width="2.5">
            <polyline points="20 6 9 17 4 12"/>
          </svg>
          <span>{{ copied ? '已复制' : '复制' }}</span>
        </button>
      </div>

      <!-- Model info badge (shown when model_info event received) -->
      <div
        v-if="modelLabel"
        :class="[
          'mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium border transition-opacity duration-300',
          modelLabel.color,
        ]"
      >
        <span>{{ modelLabel.icon }}</span>
        <span>{{ modelLabel.text }}</span>
      </div>

      <!-- Agent status badge (shown after streaming completes) -->
      <div
        v-if="statusLabel"
        :class="[
          'mt-2 inline-flex items-center gap-1.5 px-2.5 py-1 rounded-lg text-xs font-medium border transition-opacity duration-300',
          statusLabel.color,
        ]"
      >
        <span>{{ statusLabel.icon }}</span>
        <span>{{ statusLabel.text }}</span>
      </div>
    </div>

    <!-- User avatar (right side) -->
    <div v-if="isUser" class="shrink-0 mt-0.5">
      <div class="w-8 h-8 rounded-full bg-accent-700 text-white flex items-center justify-center text-xs font-semibold shadow-sm">
        U
      </div>
    </div>
  </div>
</template>
