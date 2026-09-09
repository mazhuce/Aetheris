import { act, renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { useResponsivePageSize } from './useResponsivePageSize'

type ChangeListener = () => void

describe('useResponsivePageSize', () => {
  const matches = new Map<string, boolean>()
  const listeners = new Set<ChangeListener>()

  beforeEach(() => {
    matches.clear()
    listeners.clear()
    Object.defineProperty(window, 'matchMedia', {
      configurable: true,
      value: vi.fn((query: string) => ({
        matches: matches.get(query) ?? false,
        media: query,
        onchange: null,
        addListener: () => undefined,
        removeListener: () => undefined,
        addEventListener: (_event: string, listener: ChangeListener) => listeners.add(listener),
        removeEventListener: (_event: string, listener: ChangeListener) => listeners.delete(listener),
        dispatchEvent: () => false
      }))
    })
  })

  it('uses the regular size on a tall viewport and reacts to short-screen changes', () => {
    const { result } = renderHook(() => useResponsivePageSize(8, 5, 4, 3))
    expect(result.current).toBe(8)

    matches.set('(max-height: 920px)', true)
    act(() => listeners.forEach((listener) => listener()))
    expect(result.current).toBe(5)

    matches.set('(max-height: 820px)', true)
    act(() => listeners.forEach((listener) => listener()))
    expect(result.current).toBe(4)

    matches.set('(max-height: 720px)', true)
    act(() => listeners.forEach((listener) => listener()))
    expect(result.current).toBe(3)
  })
})
