import { useEffect, useState } from 'react'

// 视口高度分档。100% 缩放的常见桌面浏览器视口高度在 800~950px 之间，
// 页面顶部还有 48px 顶栏 + 40px 子导航 + 页头/统计卡片/工具栏等固定区块，
// 留给表格的实际高度有限。因此把「短屏」阈值提到 920px，
// 让这些桌面也走更少条数，避免出现整页滚动条。
const SHORT_VIEWPORT_QUERY = '(max-height: 920px)'
const COMPACT_VIEWPORT_QUERY = '(max-height: 820px)'
const TINY_VIEWPORT_QUERY = '(max-height: 720px)'

/**
 * Keeps data-heavy pages inside a 100% zoom desktop viewport.
 * Full-height screens use the normal page size; shorter laptop screens use
 * fewer rows instead of introducing a page-level scrollbar.
 *
 * 调用方可按页面「重/轻」程度传入不同参数：
 * - 含统计卡片 + 提示条的重页面传更小 regular（如 6, 5, 4, 3）
 * - 纯列表轻页面可传更大 regular（如 8, 6, 5, 3）
 */
export function useResponsivePageSize(regular = 8, short = 5, compact = 4, tiny = 3) {
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [regular, short, compact, tiny])

  return pageSize
}
