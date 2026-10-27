import http from '@/api/http'
import {
  clearAuthenticated,
  announceSessionChange,
  captureSession,
  getAuthSnapshot,
  isCurrentSession,
  logoutIntent,
  setAuthenticated,
  setAuthLoading,
  type SessionUser,
  type LogoutRevocation
} from './authState'

let bootstrap: { generation: number; promise: Promise<boolean> } | null = null

/** Rehydrate the session from the HttpOnly cookie without exposing a token to JS. */
export function bootstrapSession(force = false): Promise<boolean> {
  const current = getAuthSnapshot()
  // A failed logout may leave HttpOnly cookies alive. Reload must preserve the local logout intent.
  if (logoutIntent()) {
    if (current.status !== 'anonymous') clearAuthenticated()
    return Promise.resolve(false)
  }
  if (!force && current.status === 'authenticated') return Promise.resolve(true)
  if (bootstrap?.generation === current.generation) return bootstrap.promise

  setAuthLoading()
  const started = captureSession()
  const promise = http.get<SessionUser>('/api/auth/me')
    .then((response) => {
      // The HTTP refresh may have already accepted a new session for this probe.
      if (!isCurrentSession(started)) return getAuthSnapshot().status === 'authenticated'
      setAuthenticated(response.data)
      return true
    })
    .catch((error) => {
      if (isCurrentSession(started)) clearAuthenticated()
      if (error?.code === 'SESSION_CHANGED') return getAuthSnapshot().status === 'authenticated'
      return false
    })
    .finally(() => {
      if (bootstrap?.promise === promise) bootstrap = null
    })
  bootstrap = { generation: current.generation, promise }
  return promise
}

export function markLoggedIn(user: SessionUser): void {
  announceSessionChange('changed')
  setAuthenticated(user)
}

export function markLoggedOut(revocation?: LogoutRevocation): void {
  try { announceSessionChange('logout', revocation) } finally { clearAuthenticated() }
}
