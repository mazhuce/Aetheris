import { act, renderHook } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { useSerializedPolling } from './useSerializedPolling'

function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
beforeEach(() => vi.useFakeTimers())
afterEach(() => vi.useRealTimers())

describe('serialized polling', () => {
  it('observes a rejected automatic refresh when scheduling the next batch fails', async () => {
    const warning = vi.spyOn(console, 'warn').mockImplementation(() => undefined)
    const schedule = vi.spyOn(window, 'setTimeout').mockImplementationOnce(() => { throw new Error('fixture scheduling failure') })
    const load = vi.fn().mockResolvedValue(1)
    const apply = vi.fn()
    const { unmount } = renderHook(() => useSerializedPolling(load, apply, 5000))
    try {
      await act(async () => { await Promise.resolve(); await Promise.resolve() })
      expect(apply).toHaveBeenCalledWith(1)
      expect(warning).toHaveBeenCalledExactlyOnceWith('Automatic polling stopped after scheduling failure')
      expect(load).toHaveBeenCalledTimes(1)
      expect(vi.getTimerCount()).toBe(0)
    } finally { unmount(); schedule.mockRestore(); warning.mockRestore() }
  })

  it('does not overlap slow batches and coalesces manual refresh requests', async () => {
    const first = deferred<number>()
    const second = deferred<number>()
    const load = vi.fn().mockReturnValueOnce(first.promise).mockReturnValueOnce(second.promise).mockResolvedValue(3)
    const apply = vi.fn()
    const { result, unmount } = renderHook(() => useSerializedPolling(load, apply, 5000))
    await act(() => vi.advanceTimersByTimeAsync(20_000))
    expect(load).toHaveBeenCalledTimes(1)
    act(() => { void result.current(); void result.current() })
    await act(async () => { first.resolve(1); await Promise.resolve() })
    expect(load).toHaveBeenCalledTimes(2)
    await act(async () => { second.resolve(2); await Promise.resolve() })
    expect(apply.mock.calls.map(([value]) => value)).toEqual([1, 2])
    await act(() => vi.advanceTimersByTimeAsync(5000))
    expect(load).toHaveBeenCalledTimes(3)
    unmount()
  })

  it('aborts network work and prevents delayed state updates after unmount', async () => {
    const batch = deferred<number>()
    const load = vi.fn((_signal: AbortSignal) => batch.promise)
    const apply = vi.fn()
    const { unmount } = renderHook(() => useSerializedPolling(load, apply, 5000))
    const signal = load.mock.calls[0][0]
    unmount()
    expect(signal.aborted).toBe(true)
    await act(async () => { batch.resolve(1); await Promise.resolve() })
    expect(apply).not.toHaveBeenCalled()
    expect(vi.getTimerCount()).toBe(0)
  })
})
