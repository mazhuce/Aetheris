/**
 * 业务域级懒加载预取：导航 hover 时先取下一页 chunk，点击后直接渲染。
 * 只预取当前主闭环相关页面，避免启动时重新引入所有功能模块。
 */
const loaders: Array<[string, () => Promise<unknown>]> = [
  ['/integration/sources', () => import('@/pages/datasource')],
  ['/integration/tasks', () => import('@/pages/ingestion')],
  ['/governance/quality', () => import('@/pages/quality/rules')],
  ['/governance/lineage', () => import('@/pages/metadata/lineage')],
  ['/modeling/metrics', () => import('@/pages/modeling/metrics')],
  ['/assets/metrics', () => import('@/pages/assets/metrics')],
  ['/analytics/query', () => import('@/pages/analytics/query')],
  ['/analytics/query-audit', () => import('@/pages/analytics/query-audit')],
  ['/ai/assistant', () => import('@/pages/ai/chat')],
  ['/ai/chatbi', () => import('@/pages/ai/chatbi')]
]

export function prefetchRoute(path: string) {
  const loader = loaders.find(([prefix]) => path === prefix || path.startsWith(`${prefix}/`))?.[1]
  if (loader) void loader().catch(() => undefined)
}
