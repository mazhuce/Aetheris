import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    testTimeout: 30_000,
    // 本机 Docker 常驻占用内存，默认按 CPU 数并发会出现资源饥饿与 15s 超时；
    // 固定 4 个 worker 保证全量测试稳定（约 5 分钟）。
    maxWorkers: 4,
    css: false
  }
})
