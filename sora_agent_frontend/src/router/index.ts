import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'

const routes: RouteRecordRaw[] = [
  {
    path: '/',
    name: 'Home',
    component: () => import('@/views/HomePage.vue'),
  },
  {
    path: '/tour',
    name: 'TourChat',
    component: () => import('@/views/TourChatPage.vue'),
  },
  {
    path: '/manus',
    name: 'ManusChat',
    component: () => import('@/views/ManusChatPage.vue'),
  },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router
