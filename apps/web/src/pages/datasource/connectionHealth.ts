import type { ConnectionHealth, ConnectionHealthStatus } from '@/api/metadata'

export function effectiveConnectionStatus(health: ConnectionHealth | undefined, now = Date.now()): ConnectionHealthStatus {
  if (!health || health.status === 'unknown') return 'unknown'
  const expiresAt = health.expiresAt ? Date.parse(health.expiresAt) : NaN
  // Missing/invalid expiry must not turn historical success into a live guarantee.
  if (!Number.isFinite(expiresAt) || expiresAt <= now) return 'unknown'
  return health.status
}

export function connectionFailureDescription(health?: ConnectionHealth): string {
  return health?.failureMessage || '连接探测失败，请检查数据源配置及依赖服务。'
}
