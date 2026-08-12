<script setup lang="ts">
import { ref, nextTick, watch, onBeforeUnmount, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import ChatBubble from './ChatBubble.vue'
import ChatInput from './ChatInput.vue'
import ConversationListPanel from './ConversationListPanel.vue'
import { createSSEConnection } from '@/utils/sse'
import { safeCopy } from '@/utils/clipboard'
import type {
  ChatMessage,
  AgentState,
  ModelOption,
  ModelInfo,
  ConversationSummary,
  HistoryMessage,
} from '@/types/chat'

export interface ChatPageConfig {
  /** SSE URL builder: (message, chatId?, model?) => full URL */
  buildUrl: (message: string, chatId?: string, model?: string) => string
  /** Whether to include chatId parameter */
  useChatId: boolean
  /** Page title */
  title: string
  /** Page subtitle / description */
  subtitle: string
  /** 是否显示「对话记录」按钮（会话管理能力，仅 Manus 页开启） */
  showConversationList?: boolean
  /** 会话列表接口地址 */
  conversationsUrl?: string
  /** 会话历史接口地址（用 {id} 占位会话 id） */
  conversationMessagesUrl?: string
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

// --- Route / session identity ---
const route = useRoute()
const router = useRouter()

function resolveChatIdFromUrl(): string {
  const q = route.query.chat
  return typeof q === 'string' && q ? q : crypto.randomUUID()
}

// --- State ---
const messages = ref<ChatMessage[]>([])
// 会话 id：优先从 URL（/manus?chat=xxx）恢复，否则新建
const chatId = ref<string>(resolveChatIdFromUrl())
const isStreaming = ref(false)
const sseConnection = ref<{ abort: () => void } | null>(null)
const agentState = ref<AgentState | null>(null)
const messagesContainer = ref<HTMLElement | null>(null)
const chatInputRef = ref<InstanceType<typeof ChatInput> | null>(null)

// --- 对话记录 ---
const conversations = ref<ConversationSummary[]>([])
const conversationsLoading = ref(false)
const showConversationPanel = ref(false)

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
function sendMessage(rawText: string) {
  if (isStreaming.value || !rawText.trim()) return

  // /skill名 斜杠命令 → 显式激活技能
  let text = rawText
  const skillMatch = text.trim().match(/^\/skill\s+(\S+)/)
  if (skillMatch) {
    const skillName = skillMatch[1]
    text = `请激活并使用技能「${skillName}」，按该技能的指南完成任务`
  }

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
  agentState.value = null
  showConversationPanel.value = false
  // 清除 URL 上的会话参数，回到全新会话
  if (props.config.useChatId) {
    router.replace({ query: {} })
  }
}

async function copyMessage(content: string) {
  await safeCopy(content)
}

// --- 对话记录 ---
function toggleConversationPanel() {
  showConversationPanel.value = !showConversationPanel.value
  if (showConversationPanel.value && conversations.value.length === 0) {
    fetchConversations()
  }
}

async function fetchConversations() {
  if (!props.config.conversationsUrl) return
  conversationsLoading.value = true
  try {
    const resp = await fetch(props.config.conversationsUrl)
    const json = await resp.json()
    if (json?.data) {
      conversations.value = json.data as ConversationSummary[]
    }
  } catch {
    console.warn('获取会话列表失败')
  } finally {
    conversationsLoading.value = false
  }
}

async function switchConversation(id: string) {
  if (id === chatId.value && messages.value.length > 0) {
    showConversationPanel.value = false
    return
  }
  if (isStreaming.value) {
    stopStreaming()
  }
  chatId.value = id
  currentModelInfo.value = null
  agentState.value = null
  if (props.config.useChatId) {
    router.replace({ query: { chat: id } })
  }
  await loadHistory(id)
  showConversationPanel.value = false
}

async function loadHistory(id: string) {
  if (!props.config.conversationMessagesUrl) return
  try {
    const url = props.config.conversationMessagesUrl.replace('{id}', encodeURIComponent(id))
    const resp = await fetch(url)
    const json = await resp.json()
    if (json?.data) {
      const history = json.data as HistoryMessage[]
      messages.value = history.map((m) => ({
        id: crypto.randomUUID(),
        role: m.role,
        content: m.content,
        timestamp: Date.now(),
      }))
    }
  } catch {
    console.warn('加载会话历史失败')
  }
}

// 浏览器前进/后退导致的 chat 参数变化 → 切换会话（switchConversation 内已有防抖守卫）
watch(
  () => route.query.chat,
  (v) => {
    const cid = typeof v === 'string' && v ? v : null
    if (cid && cid !== chatId.value) {
      switchConversation(cid)
    }
  },
)

// --- Lifecycle ---
onMounted(() => {
  fetchModels()
  // 若 URL 带了 chat 参数，恢复该会话历史
  const q = route.query.chat
  if (typeof q === 'string' && q && props.config.useChatId) {
    loadHistory(q)
  }
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

        <div class="flex items-center gap-2 relative">
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

          <!-- Conversation history button -->
          <button
            v-if="config.showConversationList"
            class="flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium
                   text-warm-500 hover:text-accent-600 hover:bg-accent-50
                   rounded-lg transition-colors duration-150 shrink-0"
            title="对话记录"
            @click="toggleConversationPanel"
          >
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <rect x="3" y="4" width="18" height="16" rx="2"/>
              <line x1="8" y1="9" x2="16" y2="9"/>
              <line x1="8" y1="13" x2="13" y2="13"/>
            </svg>
            <span>对话记录</span>
          </button>

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

          <!-- Conversation list panel -->
          <ConversationListPanel
            v-if="config.showConversationList"
            :visible="showConversationPanel"
            :loading="conversationsLoading"
            :conversations="conversations"
            :active-id="chatId"
            @select="switchConversation"
            @close="showConversationPanel = false"
          />
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
