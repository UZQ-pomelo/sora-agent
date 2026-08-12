<script setup lang="ts">
import type { ConversationSummary } from '@/types/chat'

defineProps<{
  visible: boolean
  loading: boolean
  conversations: ConversationSummary[]
  /** 当前会话 id（高亮显示） */
  activeId?: string
}>()

const emit = defineEmits<{
  (e: 'select', id: string): void
  (e: 'close'): void
}>()

function formatTime(t: string | null): string {
  if (!t) return ''
  const d = new Date(t)
  if (Number.isNaN(d.getTime())) return ''
  return d.toLocaleString('zh-CN', {
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}
</script>

<template>
  <Transition name="fade">
    <div
      v-if="visible"
      class="absolute right-0 top-12 z-30 w-80 max-h-96 overflow-y-auto rounded-xl border border-warm-200 bg-white shadow-lg"
      @click.stop
    >
      <div
        class="px-4 py-3 border-b border-warm-100 text-sm font-semibold text-warm-700 flex items-center justify-between"
      >
        <span>对话记录</span>
        <button class="text-warm-400 hover:text-warm-600 text-xs" @click="emit('close')">
          关闭
        </button>
      </div>

      <div v-if="loading" class="p-4 text-xs text-warm-400 text-center">加载中…</div>
      <div v-else-if="conversations.length === 0" class="p-4 text-xs text-warm-400 text-center">
        暂无对话记录
      </div>
      <ul v-else class="py-1">
        <li v-for="c in conversations" :key="c.conversationId">
          <button
            class="w-full text-left px-4 py-2.5 hover:bg-warm-50 transition-colors"
            :class="c.conversationId === activeId ? 'bg-accent-50' : ''"
            @click="emit('select', c.conversationId)"
          >
            <span class="block text-sm text-warm-700 truncate">
              {{ c.title || '(无标题对话)' }}
            </span>
            <span class="block text-xs text-warm-400 mt-0.5">
              {{ c.messageCount }} 条 · {{ formatTime(c.lastTime) }}
            </span>
          </button>
        </li>
      </ul>
    </div>
  </Transition>
</template>

<style scoped>
.fade-enter-active,
.fade-leave-active {
  transition: opacity 0.15s ease;
}
.fade-enter-from,
.fade-leave-to {
  opacity: 0;
}
</style>
