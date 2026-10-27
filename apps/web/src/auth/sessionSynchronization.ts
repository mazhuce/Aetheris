import { clearAuthenticated, getAuthSnapshot, resetAuthState, sessionContextRevision, SESSION_CONTEXT_KEY } from './authState'
import { bootstrapSession } from './session'

/** Revalidate on tab activation and periodically while visible; do not overlap probes. */
export function startSessionSynchronization(): () => void {
  const revalidate = () => {
    if (document.visibilityState === 'visible' && getAuthSnapshot().status === 'authenticated') {
      bootstrapSession(true).catch(() => undefined)
    }
  }
  const storage = (event: StorageEvent) => {
    if (event.key !== SESSION_CONTEXT_KEY && event.key !== null) return
    const current = sessionContextRevision()
    if (event.key === SESSION_CONTEXT_KEY && event.newValue !== current) return
    if (event.key === null && current) return
    let kind: unknown
    try { kind = event.newValue ? JSON.parse(event.newValue).kind : null } catch { /* Revalidate malformed events. */ }
    if (kind === 'logout') clearAuthenticated()
    else { resetAuthState(); bootstrapSession().catch(() => undefined) }
  }
  window.addEventListener('storage', storage)
  window.addEventListener('focus', revalidate)
  document.addEventListener('visibilitychange', revalidate)
  const timer = window.setInterval(revalidate, 30_000)
  return () => {
    window.removeEventListener('storage', storage)
    window.removeEventListener('focus', revalidate)
    document.removeEventListener('visibilitychange', revalidate)
    window.clearInterval(timer)
  }
}
