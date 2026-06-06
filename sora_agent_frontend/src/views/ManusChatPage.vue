<script setup lang="ts">
import ChatContainer from '@/components/ChatContainer.vue'
import type { ChatPageConfig } from '@/components/ChatContainer.vue'

const config: ChatPageConfig = {
  buildUrl: (message: string, _chatId?: string, model?: string) => {
    let base = `/api/ai/manus/chat?message=${message}`
    if (model) base += `&model=${model}`
    return base
  },
  useChatId: false,
  title: 'AI 智能体',
  subtitle: '通用智能助手 · 编程辅助 · 深度推理',
}
</script>

<template>
  <div class="h-screen flex flex-col bg-warm-50">
    <ChatContainer :config="config" />
  </div>
</template>
