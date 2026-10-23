import { useCallback, useEffect, useRef, useState } from 'react'
import type { VisibleListPage } from '@/api/metadata'

/** Only metadata's three cursor-list pages use this request-local navigation state. */
export function useVisibleListPage<T>(
  load: (cursor: string | undefined, signal: AbortSignal) => Promise<VisibleListPage<T>>,
  queryKey: string,
) {
  const loadRef = useRef(load)
  loadRef.current = load
  const epoch = useRef(0)
  const [navigation, setNavigation] = useState<{ key: string; cursors: (string | undefined)[]; index: number }>({ key: queryKey, cursors: [undefined], index: 0 })
  const current = navigation.key === queryKey ? navigation : { key: queryKey, cursors: [undefined], index: 0 }
  const cursor = current.cursors[current.index]
  const [revision, setRevision] = useState(0)
  const [result, setResult] = useState<{ key: string; cursor: string | undefined; page: VisibleListPage<T> }>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    const request = ++epoch.current
    const controller = new AbortController()
    setLoading(true)
    setError(null)
    loadRef.current(cursor, controller.signal).then((page) => {
      if (controller.signal.aborted || request !== epoch.current) return
      if (!Array.isArray(page.items) || typeof page.hasMore !== 'boolean'
        || (page.hasMore ? !page.nextCursor || page.nextCursor === cursor : page.nextCursor !== null)) throw new Error('分页响应无效')
      setResult({ key: queryKey, cursor, page })
    }).catch((failure: unknown) => {
      if (controller.signal.aborted || request !== epoch.current) return
      const status = (failure as { response?: { status?: number } } | null)?.response?.status
      setError(status === 503 ? '权限服务暂时不可用，请稍后重试' : '列表暂时无法加载，请重试')
    }).finally(() => {
      if (!controller.signal.aborted && request === epoch.current) setLoading(false)
    })
    return () => { controller.abort(); epoch.current += 1 }
  }, [queryKey, cursor, revision])

  const page = result?.key === queryKey && result.cursor === cursor ? result.page : undefined
  const refresh = useCallback(() => {
    setNavigation({ key: queryKey, cursors: [undefined], index: 0 })
    setRevision((value) => value + 1)
  }, [queryKey])
  const next = () => {
    if (loading || error || !page?.hasMore || !page.nextCursor) return
    setNavigation({ key: queryKey, cursors: [...current.cursors.slice(0, current.index + 1), page.nextCursor], index: current.index + 1 })
  }
  const previous = () => {
    if (loading || current.index === 0) return
    setNavigation({ ...current, index: current.index - 1 })
  }
  return { items: page?.items ?? [], hasMore: page?.hasMore ?? false, loading, error,
    pageNumber: current.index + 1, canPrevious: current.index > 0, next, previous, refresh }
}
