<script setup lang="ts">
import { ref } from 'vue'

const cards = ref([
  {
    title: 'AI 旅游助手',
    subtitle: 'Tour Assistant',
    description: '智能规划你的旅行路线，获取目的地推荐、景点介绍和出行建议。',
    icon: '🌍',
    to: '/tour',
    accent: 'accent',
    stats: [
      { label: '智能规划', value: '路线推荐' },
      { label: '实时建议', value: '景点美食' },
    ],
  },
  {
    title: 'AI 智能体',
    subtitle: 'Manus Agent',
    description: '通用智能助手，回答各类问题，辅助编程、写作、分析等多场景任务。',
    icon: '🤖',
    to: '/manus',
    accent: 'amber',
    stats: [
      { label: '多场景', value: '通用问答' },
      { label: '深度推理', value: '复杂任务' },
    ],
  },
])

const hoveredCard = ref<string | null>(null)
</script>

<template>
  <div class="min-h-screen bg-noise">
    <!-- Subtle top gradient bar -->
    <div class="h-1 bg-gradient-to-r from-accent-400 via-accent-500 to-amber-400"></div>

    <div class="max-w-5xl mx-auto px-8 py-16">
      <!-- Header -->
      <header class="text-center mb-16">
        <div class="inline-flex items-center gap-2 mb-4">
          <span class="px-2.5 py-0.5 text-[11px] font-medium tracking-wide uppercase
                       bg-accent-50 text-accent-600 rounded-full">
            AI Platform
          </span>
        </div>
        <h1 class="text-5xl font-bold text-warm-800 mb-3 tracking-tight font-display">
          Sora Agent
        </h1>
        <p class="text-lg text-warm-400 max-w-md mx-auto leading-relaxed">
          选择你想要使用的 AI 助手，开始智能对话体验
        </p>
      </header>

      <!-- Cards -->
      <div class="grid grid-cols-2 gap-8 max-w-4xl mx-auto">
        <router-link
          v-for="card in cards"
          :key="card.to"
          :to="card.to"
          class="group relative block rounded-2xl p-8 transition-all duration-500 ease-out
                 hover:-translate-y-1.5"
          :class="[
            hoveredCard === card.to
              ? 'shadow-xl shadow-accent-100/60'
              : 'shadow-sm',
            hoveredCard === null
              ? 'shadow-sm hover:shadow-lg hover:shadow-accent-100/40'
              : '',
            hoveredCard && hoveredCard !== card.to
              ? 'opacity-60 scale-[0.98]'
              : 'opacity-100 scale-100',
          ]"
          @mouseenter="hoveredCard = card.to"
          @mouseleave="hoveredCard = null"
        >
          <!-- Card background -->
          <div
            class="absolute inset-0 rounded-2xl bg-white border border-warm-200/80
                   transition-shadow duration-500"
          ></div>

          <!-- Decorative corner -->
          <div
            class="absolute top-0 right-0 w-24 h-24 rounded-bl-2xl opacity-[0.03]
                   transition-opacity duration-300 group-hover:opacity-[0.06]"
            :class="card.accent === 'accent' ? 'bg-accent-600' : 'bg-amber-500'"
          ></div>

          <!-- Content -->
          <div class="relative z-10">
            <!-- Icon -->
            <div
              class="w-14 h-14 rounded-xl flex items-center justify-center text-2xl mb-5
                     transition-all duration-300 group-hover:scale-110"
              :class="card.accent === 'accent'
                ? 'bg-accent-50 shadow-sm shadow-accent-100/50'
                : 'bg-amber-50 shadow-sm shadow-amber-100/50'"
            >
              {{ card.icon }}
            </div>

            <!-- Title -->
            <h2
              class="text-xl font-bold text-warm-800 mb-1 font-display
                     transition-colors duration-300"
              :class="card.accent === 'accent'
                ? 'group-hover:text-accent-700'
                : 'group-hover:text-amber-700'"
            >
              {{ card.title }}
            </h2>
            <p class="text-xs font-medium tracking-wide uppercase mb-4"
               :class="card.accent === 'accent' ? 'text-accent-400' : 'text-amber-400'">
              {{ card.subtitle }}
            </p>
            <p class="text-sm text-warm-500 leading-relaxed mb-6">
              {{ card.description }}
            </p>

            <!-- Stats -->
            <div class="flex gap-4 pt-4 border-t border-warm-100">
              <div v-for="stat in card.stats" :key="stat.label" class="flex-1">
                <p class="text-xs text-warm-400 mb-0.5">{{ stat.label }}</p>
                <p class="text-sm font-semibold text-warm-600">{{ stat.value }}</p>
              </div>
            </div>

            <!-- Hover indicator arrow -->
            <div
              class="absolute bottom-8 right-8 w-8 h-8 rounded-full flex items-center justify-center
                     opacity-0 translate-x-1 transition-all duration-300
                     group-hover:opacity-100 group-hover:translate-x-0"
              :class="card.accent === 'accent'
                ? 'bg-accent-100 text-accent-600'
                : 'bg-amber-100 text-amber-600'"
            >
              <svg class="w-4 h-4" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
                <line x1="5" y1="12" x2="19" y2="12"/>
                <polyline points="12 5 19 12 12 19"/>
              </svg>
            </div>
          </div>
        </router-link>
      </div>

      <!-- Footer hint -->
      <p class="text-center text-xs text-warm-300 mt-12 select-none">
        点击卡片进入对应的 AI 对话界面
      </p>
    </div>
  </div>
</template>
