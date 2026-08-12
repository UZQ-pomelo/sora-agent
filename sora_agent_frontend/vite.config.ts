import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(({ mode }) => {
  // 用 loadEnv 显式加载 .env 文件：config 求值时 process.env 尚未注入 .env 内容，
  // 直接在 configure 回调里读 process.env.VITE_API_KEY 可能拿到空值
  const env = loadEnv(mode, process.cwd(), '')
  const apiKey = env.VITE_API_KEY

  return {
    plugins: [vue()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url)),
      },
    },
    server: {
      port: 5173,
      proxy: {
        '/api': {
          target: 'http://localhost:8080',
          changeOrigin: true,
          // 开发模式：由 vite 代理在服务端注入 API Key（后端启用认证时需要）。
          // key 来自 sora_agent_frontend/.env 的 VITE_API_KEY（本地文件，gitignored），
          // 在 vite 进程侧注入，不进入浏览器 JS
          configure: (proxy) => {
            if (apiKey) {
              proxy.on('proxyReq', (proxyReq) => {
                proxyReq.setHeader('X-API-Key', apiKey)
              })
            }
          },
        },
      },
    },
  }
})
