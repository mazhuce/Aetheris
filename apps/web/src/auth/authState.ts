import type { UserRole } from '@/api/auth'

export interface SessionUser {
  username: string
  nickname?: string
  role: UserRole
}

export type AuthSnapshot =
  | { status: 'unknown' | 'loading' | 'anonymous'; user: null }
  | { status: 'authenticated'; user: SessionUser }

let snapshot: AuthSnapshot = { status: 'unknown', user: null }
const listeners = new Set<() => void>()

export function getAuthSnapshot(): AuthSnapshot {
  return snapshot
}

export function subscribeAuth(listener: () => void): () => void {
  listeners.add(listener)
  return () => listeners.delete(listener)
}

function publish(next: AuthSnapshot) {
  snapshot = next
  listeners.forEach((listener) => listener())
}

export function setAuthenticated(user: SessionUser): void {
  publish({ status: 'authenticated', user })
}

export function setAuthLoading(): void {
  if (snapshot.status !== 'authenticated') publish({ status: 'loading', user: null })
}

export function clearAuthenticated(): void {
  publish({ status: 'anonymous', user: null })
}

/** Test and hot-reload helper; production code should use clearAuthenticated. */
export function resetAuthState(): void {
  publish({ status: 'unknown', user: null })
}
