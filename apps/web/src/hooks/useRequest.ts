import { useCallback, useEffect, useRef, useState } from 'react'

export function useRequest<T>(fetcher: () => Promise<T>, immediate = true) {
  const [data, setData] = useState<T>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>()
  const fetcherRef = useRef(fetcher)
  const requestIdRef = useRef(0)
  const mountedRef = useRef(false)
  fetcherRef.current = fetcher

  useEffect(() => {
    mountedRef.current = true
    return () => {
      mountedRef.current = false
      // 使卸载前发出的请求失效；其结果和异常都不能再写入已卸载组件。
      requestIdRef.current += 1
    }
  }, [])

  const run = useCallback(async () => {
    if (!mountedRef.current) return
    const requestId = ++requestIdRef.current
    setLoading(true)
    setError(undefined)
    try {
      const nextData = await fetcherRef.current()
      if (mountedRef.current && requestIdRef.current === requestId) {
        setData(nextData)
      }
    } catch (err) {
      if (mountedRef.current && requestIdRef.current === requestId) {
        setError(err)
      }
    } finally {
      // 并发 run 时，只有最后一次请求可以结束 loading，避免旧请求覆盖新状态。
      if (mountedRef.current && requestIdRef.current === requestId) {
        setLoading(false)
      }
    }
  }, [])

  useEffect(() => {
    if (immediate) void run()
  }, [immediate, run])

  return { data, loading, error, run }
}
