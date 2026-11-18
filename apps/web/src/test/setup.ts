import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup, configure } from '@testing-library/react'
import { resetAuthState } from '@/auth/authState'

// 全量并发跑测试时表单校验与异步渲染可能超过默认 1s，放宽异步查询等待。
configure({ asyncUtilTimeout: 4000 })

Object.defineProperty(window, 'matchMedia', {
  writable: true,
  value: (query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addListener: () => undefined,
    removeListener: () => undefined,
    addEventListener: () => undefined,
    removeEventListener: () => undefined,
    dispatchEvent: () => false
  })
})

beforeEach(() => {
  let tail: Promise<unknown> = Promise.resolve()
  Object.defineProperty(navigator, 'locks', { configurable: true, value: {
    request: (_name: string, run: () => Promise<unknown>) => {
      const pending = tail.then(run)
      tail = pending.catch(() => undefined)
      return pending
    }
  } })
})

afterEach(() => {
  cleanup()
  localStorage.clear()
  resetAuthState()
  vi.restoreAllMocks()
})
