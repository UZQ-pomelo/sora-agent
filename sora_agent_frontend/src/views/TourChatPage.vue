<script setup lang="ts">
import ChatContainer from '@/components/ChatContainer.vue'
import type { ChatPageConfig } from '@/components/ChatContainer.vue'

const config: ChatPageConfig = {
  buildUrl: (message: string, chatId?: string) => {
    const base = `/api/ai/tour_app/chat/sse?message=${message}`
    return chatId ? `${base}&chatId=${chatId}` : base
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
