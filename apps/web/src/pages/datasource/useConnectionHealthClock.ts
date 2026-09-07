import { useEffect, useState } from 'react'
import type { DataSource } from '@/api/metadata'

/** Expire displayed results locally. Opening or keeping this page open never starts a probe. */
export function useConnectionHealthClock(dataSources: DataSource[]): number {
  const [revision, setRevision] = useState(0)
  const now = Date.now()

  useEffect(() => {
    const refresh = () => setRevision((value) => value + 1)
    const currentTime = Date.now()
    const expiries = dataSources
      .map((item) => Date.parse(item.connectionHealth?.expiresAt || ''))
      .filter((expiry) => Number.isFinite(expiry) && expiry > currentTime)
    const timer = expiries.length
      ? window.setTimeout(refresh, Math.min(Math.min(...expiries) - currentTime, 2_147_483_647))
      : undefined
    window.addEventListener('focus', refresh)
    document.addEventListener('visibilitychange', refresh)
    return () => {
      window.clearTimeout(timer)
      window.removeEventListener('focus', refresh)
      document.removeEventListener('visibilitychange', refresh)
    }
  }, [dataSources, revision])

  return now
}
