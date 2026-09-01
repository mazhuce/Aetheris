import { renderHook, waitFor, act } from '@testing-library/react'
import { useRequest } from './useRequest'

describe('useRequest', () => {
  it('immediate 默认自动请求并写入 data', async () => {
    const { result } = renderHook(() => useRequest(() => Promise.resolve('hello')))
    expect(result.current.loading).toBe(true)
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBe('hello')
    expect(result.current.error).toBeUndefined()
  })

  it('请求失败时写入 error 且不抛出', async () => {
    const failure = () => Promise.reject(new Error('boom'))
    const { result } = renderHook(() => useRequest(failure))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.data).toBeUndefined()
    expect(result.current.error).toBeInstanceOf(Error)
    expect((result.current.error as Error).message).toBe('boom')
  })

  it('immediate 为 false 时不自动请求，run 手动触发', async () => {
    const fetcher = vi.fn(() => Promise.resolve(42))
    const { result } = renderHook(() => useRequest(fetcher, false))
    expect(fetcher).not.toHaveBeenCalled()
    await act(async () => {
      await result.current.run()
    })
    expect(fetcher).toHaveBeenCalledTimes(1)
    expect(result.current.data).toBe(42)
    expect(result.current.loading).toBe(false)
  })

  it('每次 run 使用最新的 fetcher 闭包', async () => {
    let value = 1
    const { result, rerender } = renderHook(() => useRequest(() => Promise.resolve(value), false))
    value = 2
    rerender()
    await act(async () => {
      await result.current.run()
    })
    expect(result.current.data).toBe(2)
  })
})
