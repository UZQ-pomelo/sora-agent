<script setup lang="ts">
import { ref, nextTick, watch, onBeforeUnmount, computed, onMounted } from 'vue'
import ChatBubble from './ChatBubble.vue'
import ChatInput from './ChatInput.vue'
import { createSSEConnection } from '@/utils/sse'
import type { ChatMessage, AgentState, ModelOption, ModelInfo } from '@/types/chat'

export interface ChatPageConfig {
  /** SSE URL builder: (message, chatId?, model?) => full URL */
  buildUrl: (message: string, chatId?: string, model?: string) => string
  /** Whether to include chatId parameter */
  useChatId: boolean
  /** Page title */
  title: string
  /** Page subtitle / description */
  subtitle: string
}

const props = defineProps<{
  config: ChatPageConfig
}>()

// --- Model state ---
const selectedModel = ref<string>('')
const availableModels = ref<ModelOption[]>([])
const currentModelInfo = ref<ModelInfo | null>(null)

async function fetchModels() {
  try {
    const resp = await fetch('/api/ai/models')
    const json = await resp.json()
    if (json?.data) {
      availableModels.value = json.data.models || []
      selectedModel.value = json.data.default || ''
    }
  } catch {
    // 降级：硬编码默认值
    console.warn('无法获取模型列表，使用默认')
  }
}

function onModelSelect(modelName: string) {
  selectedModel.value = modelName
  currentModelInfo.value = null
}

// --- State ---
const messages = ref<ChatMessage[]>([])
const chatId = ref<string>(crypto.randomUUID())
const isStreaming = ref(false)
const sseConnection = ref<{ abort: () => void } | null>(null)
const agentState = ref<AgentState | null>(null)
const messagesContainer = ref<HTMLElement | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

const hasMessages = computed(() => messages.value.length > 0)

// --- Auto-scroll ---
function scrollToBottom(smooth = true) {
  nextTick(() => {
    const el = messagesContainer.value
    if (el) {
      el.scrollTo({
        top: el.scrollHeight,
        behavior: smooth ? 'smooth' : 'auto',
      })
    }
  })
}

watch(messages, () => scrollToBottom(), { deep: true })

// --- SSE ---
function sendMessage(text: string) {
  if (isStreaming.value || !text.trim()) return

  // Add user message
  const userMsg: ChatMessage = {
    id: crypto.randomUUID(),
    role: 'user',
    content: text,
    timestamp: Date.now(),
  }
  messages.value.push(userMsg)
  agentState.value = null
  currentModelInfo.value = null

  // Create placeholder assistant message
  messages.value.push({
    id: crypto.randomUUID(),
    role: 'assistant',
    content: '',
    timestamp: Date.now(),
  })
  const reactiveMsg = messages.value[messages.value.length - 1]

  isStreaming.value = true

  // Build URL with model param
  const url = props.config.useChatId
    ? props.config.buildUrl(encodeURIComponent(text), chatId.value, selectedModel.value)
    : props.config.buildUrl(encodeURIComponent(text), undefined, selectedModel.value)

  const es = createSSEConnection({
    url,
    onMessage(chunk: string) {
      reactiveMsg.content += chunk
    },
    onError(error: string) {
      if (error.startsWith('⚠️')) {
        if (!reactiveMsg.content) {
          reactiveMsg.content = error
        }
      }
    },
    onComplete() {
      isStreaming.value = false
      sseConnection.value = null
    },
    onAgentState(state: AgentState) {
      agentState.value = state
    },
    onModelInfo(info: ModelInfo) {
      currentModelInfo.value = info
    },
  })

  sseConnection.value = es
}

function stopStreaming() {
  if (sseConnection.value) {
    sseConnection.value.abort()
    sseConnection.value = null
  }
  isStreaming.value = false
}

function newConversation() {
  if (isStreaming.value) {
    stopStreaming()
  }
  messages.value = []
  chatId.value = crypto.randomUUID()
  currentModelInfo.value = null
}

function copyMessage(content: string) {
  navigator.clipboard.writeText(content)
}

// --- Lifecycle ---
onMounted(() => {
  fetchModels()
})

onBeforeUnmount(() => {
  if (sseConnection.value) {
    sseConnection.value.abort()
  }
})

// --- Expose for parent ---
defineExpose({ newConversation })
</script>

<template>
  <div class="flex flex-col h-full">
    <!-- Top bar -->
    <header
      class="shrink-0 border-b border-warm-200 bg-white/90 backdrop-blur-sm sticky top-0 z-10"
    >
      <div class="max-w-3xl mx-auto px-6 py-3 flex items-center justify-between">
        <div class="flex items-center gap-3 min-w-0">
          <!-- Back button -->
          <router-link
            to="/"
            class="flex items-center justify-center w-8 h-8 rounded-lg
                   text-warm-400 hover:text-warm-600 hover:bg-warm-100
                   transition-colors duration-150 shrink-0"
            title="返回首页"
          >
            <svg class="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <polyline points="15 18 9 12 15 6"/>
            </svg>
          </router-link>

          <div class="min-w-0">
            <h1 class="text-base font-semibold text-warm-800 truncate font-display">
              {{ config.title }}
            </h1>
            <p class="text-xs text-warm-400 truncate">{{ config.subtitle }}</p>
          </div>
        </div>

        <div class="flex items-center gap-2">
          <!-- Model selector -->
          <select
            v-model="selectedModel"
            @change="onModelSelect(($event.target as HTMLSelectElement).value)"
            class="text-xs bg-warm-50 border border-warm-200 rounded-lg px-2.5 py-1.5
                   text-warm-600 focus:outline-none focus:border-accent-300 focus:ring-1
                   focus:ring-accent-100 transition-colors cursor-pointer
                   hover:border-warm-300"
            title="选择模型"
          >
            <option
              v-for="m in availableModels"
              :key="m.name"
              :value="m.name"
            >
              {{ m.display }}
            </option>
          </select>

          <!-- New conversation button -->
          <button
            class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium
                   text-warm-500 hover:text-accent-600 hover:bg-accent-50
                   rounded-lg transition-colors duration-150 shrink-0"
            @click="newConversation"
          >
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="12" y1="5" x2="12" y2="19"/>
              <line x1="5" y1="12" x2="19" y2="12"/>
            </svg>
            <span>新建对话</span>
          </button>
        </div>
      </div>
    </header>

    <!-- Messages area -->
    <main
      ref="messagesContainer"
      class="flex-1 overflow-y-auto"
    >
      <!-- Empty state -->
      <div
        v-if="!hasMessages"
        class="flex flex-col items-center justify-center h-full px-6 text-center"
      >
        <div
          class="w-20 h-20 rounded-2xl bg-accent-50 flex items-center justify-center mb-5
                 shadow-sm"
        >
          <svg class="w-10 h-10 text-accent-300" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>
            <line x1="9" y1="10" x2="15" y2="10"/>
            <line x1="12" y1="7" x2="12" y2="13"/>
          </svg>
        </div>
        <h2 class="text-lg font-semibold text-warm-700 mb-1 font-display">
          {{ config.title }}
        </h2>
        <p class="text-sm text-warm-400 max-w-sm text-balance">
          {{ config.subtitle }}。在下方输入消息开始对话。
        </p>
      </div>

      <!-- Message list -->
      <div v-else class="max-w-3xl mx-auto px-6 py-6">
        <ChatBubble
          v-for="(msg, idx) in messages"
          :key="msg.id"
          :message="msg"
          :is-streaming="isStreaming && msg === messages[messages.length - 1] && msg.role === 'assistant'"
          :agent-state="(!isStreaming && idx === messages.length - 1 && msg.role === 'assistant') ? agentState : null"
          :model-info="(idx === messages.length - 1 && msg.role === 'assistant') ? currentModelInfo : null"
          @copy="copyMessage"
        />
        <!-- Bottom spacer for comfortable scrolling -->
        <div class="h-4"></div>
      </div>
    </main>

    <!-- Input area -->
    <footer class="shrink-0">
      <ChatInput
        ref="chatInputRef"
        :disabled="isStreaming"
        :is-streaming="isStreaming"
        @send="sendMessage"
        @stop="stopStreaming"
      />
    </footer>
  </div>
</template>
