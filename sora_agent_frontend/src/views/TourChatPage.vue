<script setup lang="ts">
import ChatContainer from '@/components/ChatContainer.vue'
import type { ChatPageConfig } from '@/components/ChatContainer.vue'

const config: ChatPageConfig = {
  buildUrl: (message: string, chatId?: string, model?: string) => {
    let base = `/api/ai/tour_app/chat/server?message=${message}`
    if (chatId) base += `&chatId=${chatId}`
    if (model) base += `&model=${model}`
    return base
  },
  useChatId: true,
  title: 'AI 旅游助手',
  subtitle: '智能旅行规划 · 目的地推荐 · 景点美食',
}
</script>

<template>
  <div class="h-screen flex flex-col bg-warm-50">
    <ChatContainer :config="config" />
  </div>
</template>
