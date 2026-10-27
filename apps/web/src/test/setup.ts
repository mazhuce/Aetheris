import '@testing-library/jest-dom/vitest'
import { afterEach, beforeEach, vi } from 'vitest'
import { cleanup } from '@testing-library/react'
import { resetAuthState } from '@/auth/authState'

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
