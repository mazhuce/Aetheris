import { act, renderHook, waitFor } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { useVisibleListPage } from './useVisibleListPage'
import type { VisibleListPage } from '@/api/metadata'

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

describe('visible cursor navigation', () => {
  it('aborts an old filter and ignores its late response', async () => {
    const first = deferred<VisibleListPage<number>>()
    const second = deferred<VisibleListPage<number>>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise)
    const { result, rerender, unmount } = renderHook(({ key }) => useVisibleListPage<number>(load, key), { initialProps: { key: 'a' } })
    const signal = load.mock.calls[0][1] as AbortSignal
    rerender({ key: 'b' })
    expect(signal.aborted).toBe(true)
    await act(async () => { second.resolve({ items: [2], nextCursor: null, hasMore: false }) })
    await act(async () => { first.resolve({ items: [1], nextCursor: null, hasMore: false }) })
    expect(result.current.items).toEqual([2])
    const currentSignal = load.mock.calls[1][1] as AbortSignal
    unmount()
    expect(currentSignal.aborted).toBe(true)
  })

  it('can advance through an empty nonterminal page and refreshes from the beginning', async () => {
    const load = vi.fn().mockResolvedValueOnce({ items: [], nextCursor: 'next', hasMore: true })
      .mockResolvedValueOnce({ items: [3], nextCursor: null, hasMore: false })
      .mockResolvedValueOnce({ items: [4], nextCursor: null, hasMore: false })
    const { result } = renderHook(() => useVisibleListPage<number>(load, 'same'))
    await waitFor(() => expect(result.current.loading).toBe(false))
    act(() => result.current.next())
    await waitFor(() => expect(result.current.items).toEqual([3]))
    expect(load.mock.calls[1][0]).toBe('next')
    act(() => result.current.refresh())
    await waitFor(() => expect(result.current.items).toEqual([4]))
    expect(load.mock.calls[2][0]).toBeUndefined()
    expect(result.current.pageNumber).toBe(1)
  })

  it('keeps the cursor on 503 and never treats it as an empty authorized result', async () => {
    const load = vi.fn().mockResolvedValueOnce({ items: [1], nextCursor: 'two', hasMore: true })
      .mockRejectedValueOnce({ response: { status: 503 } })
    const { result } = renderHook(() => useVisibleListPage<number>(load, 'same'))
    await waitFor(() => expect(result.current.loading).toBe(false))
    act(() => result.current.next())
    await waitFor(() => expect(result.current.error).toContain('权限服务'))
    expect(result.current.pageNumber).toBe(2)
    act(() => result.current.next())
    expect(load).toHaveBeenCalledTimes(2)
  })

  it('resets to the first cursor when filtering during a pending next page', async () => {
    const pending = deferred<VisibleListPage<number>>()
    const load = vi.fn().mockResolvedValueOnce({ items: [1], nextCursor: 'old-next', hasMore: true })
      .mockReturnValueOnce(pending.promise)
      .mockResolvedValueOnce({ items: [8], nextCursor: null, hasMore: false })
    const { result, rerender } = renderHook(({ key }) => useVisibleListPage<number>(load, key), { initialProps: { key: 'old' } })
    await waitFor(() => expect(result.current.loading).toBe(false))
    act(() => result.current.next())
    rerender({ key: 'filtered' })
    await waitFor(() => expect(result.current.items).toEqual([8]))
    expect(load.mock.calls[2][0]).toBeUndefined()
    expect((load.mock.calls[1][1] as AbortSignal).aborted).toBe(true)
    await act(async () => pending.resolve({ items: [2], nextCursor: null, hasMore: false }))
    expect(result.current.items).toEqual([8])
    expect(result.current.pageNumber).toBe(1)
  })
})
