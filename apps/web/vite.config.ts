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
            // Keep the UI framework and its icon registry independent so the
            // login/workbench path does not pull icon-only code into the same
            // large vendor file. Feature libraries are loaded by their route
            // chunks (dashboard/editor/AI pages) when needed.
            'antd-core': ['antd'],
            'antd-icons': ['@ant-design/icons'],
            charts: ['echarts'],
            dashboard: ['react-grid-layout'],
            markdown: ['react-markdown', 'remark-gfm'],
            react: ['react', 'react-dom', 'react-router-dom', '@tanstack/react-query'],
            vendor: ['axios', 'dayjs']
          }
        }
      }
    }
  }
})
