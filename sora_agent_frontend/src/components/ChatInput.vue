<script setup lang="ts">
import { ref, computed, nextTick } from 'vue'

const props = defineProps<{
  disabled: boolean
  isStreaming: boolean
}>()

const emit = defineEmits<{
  (e: 'send', message: string): void
  (e: 'stop'): void
}>()

const inputText = ref('')
const textareaRef = ref<HTMLTextAreaElement | null>(null)

const canSend = computed(() => inputText.value.trim().length > 0 && !props.disabled)

function autoResize() {
  const el = textareaRef.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, 200) + 'px'
}

function onInput() {
  autoResize()
}

function onKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault()
    sendMessage()
  }
}

function sendMessage() {
  if (!canSend.value) return
  emit('send', inputText.value.trim())
  inputText.value = ''
  nextTick(() => {
    if (textareaRef.value) {
      textareaRef.value.style.height = 'auto'
    }
  })
}

function onStop() {
  emit('stop')
}

// Auto-focus on mount
defineExpose({ focus: () => textareaRef.value?.focus() })
</script>

<template>
  <div class="border-t border-warm-200 bg-white/80 backdrop-blur-sm">
    <div class="max-w-3xl mx-auto px-6 py-4">
      <div class="flex items-end gap-3">
        <!-- Textarea -->
        <div class="flex-1 relative">
          <textarea
            ref="textareaRef"
            v-model="inputText"
            :disabled="disabled"
            class="w-full resize-none rounded-xl border border-warm-200 bg-warm-50 px-4 py-3 pr-4
                   text-sm text-warm-800 placeholder:text-warm-400
                   focus:outline-none focus:border-accent-300 focus:ring-2 focus:ring-accent-100
                   focus:bg-white transition-all duration-200
                   disabled:opacity-50 disabled:cursor-not-allowed"
            :class="[isStreaming ? 'pr-12' : '']"
            rows="1"
            placeholder="输入消息..."
            @input="onInput"
            @keydown="onKeydown"
          ></textarea>
          <!-- Hint text -->
          <span class="absolute right-3 bottom-2.5 text-[10px] text-warm-300 pointer-events-none select-none">
            Enter 发送 · Shift+Enter 换行
          </span>
        </div>

        <!-- Action buttons -->
        <div class="flex items-center gap-2 shrink-0">
          <!-- Send button -->
          <button
            v-if="!isStreaming"
            :disabled="!canSend"
            class="flex items-center justify-center w-10 h-10 rounded-xl
                   bg-accent-600 text-white
                   hover:bg-accent-700 active:scale-95
                   disabled:bg-warm-200 disabled:text-warm-400 disabled:cursor-not-allowed
                   transition-all duration-200 shadow-sm hover:shadow-md"
            @click="sendMessage"
            title="发送 (Enter)"
          >
            <svg class="w-5 h-5" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <line x1="22" y1="2" x2="11" y2="13"/>
              <polygon points="22 2 15 22 11 13 2 9 22 2"/>
            </svg>
          </button>

          <!-- Stop button (visible during streaming) -->
          <button
            v-else
            class="flex items-center justify-center w-10 h-10 rounded-xl
                   bg-red-100 text-red-500 hover:bg-red-200
                   active:scale-95 transition-all duration-200 shadow-sm"
            @click="onStop"
            title="停止生成"
          >
            <svg class="w-4 h-4" viewBox="0 0 24 24" fill="currentColor">
              <rect x="6" y="6" width="12" height="12" rx="1"/>
            </svg>
          </button>
        </div>
      </div>

      <!-- Disclaimer -->
      <p class="text-center text-[10px] text-warm-300 mt-2 select-none">
        内容由 AI 生成，仅供参考
      </p>
    </div>
  </div>
</template>
