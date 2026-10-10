import http from '@/api/http'
import {
  clearAuthenticated,
  getAuthSnapshot,
  setAuthenticated,
  setAuthLoading,
  type SessionUser
} from './authState'

let bootstrapPromise: Promise<boolean> | null = null

/** Rehydrate the session from the HttpOnly cookie without exposing a token to JS. */
export function bootstrapSession(): Promise<boolean> {
  const current = getAuthSnapshot()
  if (current.status === 'authenticated') return Promise.resolve(true)
  if (bootstrapPromise) return bootstrapPromise

  setAuthLoading()
  bootstrapPromise = http.get<SessionUser>('/api/auth/me')
    .then((response) => {
      setAuthenticated(response.data)
      return true
    })
    .catch(() => {
      clearAuthenticated()
      return false
    })
    .finally(() => {
      bootstrapPromise = null
    })
  return bootstrapPromise
}

export function markLoggedIn(user: SessionUser): void {
  setAuthenticated(user)
}

export function markLoggedOut(): void {
  clearAuthenticated()
}
