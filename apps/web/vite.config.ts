import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig(() => {
  const proxyTarget = process.env.API_PROXY_TARGET || 'http://localhost:8080'
  const usePolling = process.env.CHOKIDAR_USEPOLLING === 'true'
  const pollingInterval = Number(process.env.CHOKIDAR_INTERVAL || 300)

  return {
    plugins: [react()],
    resolve: {
      alias: {
        '@': fileURLToPath(new URL('./src', import.meta.url))
      }
    },
    server: {
      host: true,
      port: 5173,
      strictPort: true,
      watch: usePolling
        ? { usePolling: true, interval: pollingInterval }
        : undefined,
      proxy: {
        '/api': {
          target: proxyTarget,
          changeOrigin: true
        },
        '/actuator': {
          target: proxyTarget,
          changeOrigin: true
        }
      }
    },
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            antd: ['antd', '@ant-design/icons'],
            vendor: ['axios']
          }
        }
      }
    }
  }
})
