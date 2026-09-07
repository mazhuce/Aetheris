import { useEffect, useState } from 'react'

const SHORT_VIEWPORT_QUERY = '(max-height: 850px)'
const COMPACT_VIEWPORT_QUERY = '(max-height: 800px)'
const TINY_VIEWPORT_QUERY = '(max-height: 720px)'

/**
 * Keeps data-heavy pages inside a 100% zoom desktop viewport.
 * Full-height screens use the normal page size; shorter laptop screens use
 * fewer rows instead of introducing a page-level scrollbar.
 */
export function useResponsivePageSize(regular = 10, short = 5, compact = 4, tiny = 2) {
  const resolvePageSize = () => {
    if (window.matchMedia(TINY_VIEWPORT_QUERY).matches) return tiny
    if (window.matchMedia(COMPACT_VIEWPORT_QUERY).matches) return compact
    if (window.matchMedia(SHORT_VIEWPORT_QUERY).matches) return short
    return regular
  }

  const [pageSize, setPageSize] = useState(resolvePageSize)

  useEffect(() => {
    const mediaQueries = [
      window.matchMedia(SHORT_VIEWPORT_QUERY),
      window.matchMedia(COMPACT_VIEWPORT_QUERY),
      window.matchMedia(TINY_VIEWPORT_QUERY)
    ]
    const update = () => setPageSize(resolvePageSize())
    update()
    mediaQueries.forEach((media) => media.addEventListener('change', update))
    return () => mediaQueries.forEach((media) => media.removeEventListener('change', update))
  }, [regular, short, compact, tiny])

  return pageSize
}
