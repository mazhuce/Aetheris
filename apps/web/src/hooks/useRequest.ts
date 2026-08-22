import { useCallback, useEffect, useRef, useState } from 'react'

export function useRequest<T>(fetcher: () => Promise<T>, immediate = true) {
  const [data, setData] = useState<T>()
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<unknown>()
  const fetcherRef = useRef(fetcher)
  fetcherRef.current = fetcher

  const run = useCallback(async () => {
    setLoading(true)
    setError(undefined)
    try {
      setData(await fetcherRef.current())
    } catch (err) {
      setError(err)
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    if (immediate) void run()
  }, [immediate, run])

  return { data, loading, error, run }
}
