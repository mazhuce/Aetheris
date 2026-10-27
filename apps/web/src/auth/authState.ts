import type { UserRole } from '@/api/auth'
import { requireSessionInfrastructure, sessionUnavailable } from './browserSession'

export interface SessionUser {
  id: number
  username: string
  nickname?: string
  role: UserRole
  accessVersion: number
  permissionVersion: number
}

export type AuthSnapshot = { generation: number; contextRevision: string | null } & (
  | { status: 'unknown' | 'loading' | 'anonymous'; user: null }
  | { status: 'authenticated'; user: SessionUser })

export const SESSION_CONTEXT_KEY = 'aetheris.session.context'
export type LogoutRevocation = 'pending' | 'confirmed' | 'unconfirmed'
export function sessionContextRevision(): string | null {
  try { return localStorage.getItem(SESSION_CONTEXT_KEY) ?? '' } catch { return null }
}

export function logoutIntent(): { revocation?: LogoutRevocation } | null {
  try {
    const event = JSON.parse(sessionContextRevision() || 'null') as { kind?: string; revocation?: LogoutRevocation } | null
    return event?.kind === 'logout' ? { revocation: event.revocation } : null
  } catch { return null }
}

let snapshot: AuthSnapshot = { status: 'unknown', user: null, generation: 0, contextRevision: sessionContextRevision() }
const listeners = new Set<() => void>()

/** Server versions are required. A username alone is never a cache/replay identity. */
export function parseSessionUser(value: unknown): SessionUser {
  const user = value as Partial<SessionUser> | null
  if (!user || !Number.isSafeInteger(user.id) || user.id! <= 0
      || !Number.isSafeInteger(user.accessVersion) || user.accessVersion! < 0
      || !Number.isSafeInteger(user.permissionVersion) || user.permissionVersion! < 0
      || typeof user.username !== 'string' || !user.username.trim()
      || !['admin', 'viewer'].includes(user.role ?? '')) {
    throw new Error('服务器未提供有效的会话身份，请重新登录')
  }
  return { id: user.id!, username: user.username, role: user.role!,
    nickname: typeof user.nickname === 'string' ? user.nickname : undefined,
    accessVersion: user.accessVersion!, permissionVersion: user.permissionVersion! }
}

export function sessionIdentity(user: SessionUser): string {
  return JSON.stringify([user.id, user.accessVersion, user.permissionVersion, user.role])
}

export interface SessionStamp { generation: number; contextRevision: string | null; identity: string | null }
export function captureSession(): SessionStamp {
  return { generation: snapshot.generation, contextRevision: snapshot.contextRevision,
    identity: snapshot.user ? sessionIdentity(snapshot.user) : null }
}
export function isCurrentSession(stamp: SessionStamp): boolean {
  return stamp.contextRevision !== null && stamp.generation === snapshot.generation && stamp.contextRevision === snapshot.contextRevision
    && stamp.contextRevision === sessionContextRevision()
}

/** Share only an opaque change event; cookies and user details never enter storage. */
export function announceSessionChange(kind: 'changed' | 'logout', revocation?: LogoutRevocation): void {
  try { localStorage.setItem(SESSION_CONTEXT_KEY, JSON.stringify({ kind, nonce: crypto.randomUUID(), revocation })) } catch {
    clearAuthenticated()
    throw sessionUnavailable('浏览器会话存储不可用，请允许本站存储后重新登录')
  }
}

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
  try { requireSessionInfrastructure() } catch (error) { clearAuthenticated(); throw error }
  const parsed = parseSessionUser(user)
  const changed = !snapshot.user || sessionIdentity(snapshot.user) !== sessionIdentity(parsed)
    || snapshot.contextRevision !== sessionContextRevision()
  if (snapshot.user && changed && snapshot.contextRevision === sessionContextRevision()) announceSessionChange('changed')
  publish({ status: 'authenticated', user: parsed, generation: snapshot.generation + Number(changed),
    contextRevision: sessionContextRevision() })
}

export function setAuthLoading(): void {
  if (snapshot.status !== 'authenticated') publish({ ...snapshot, status: 'loading', user: null })
}

export function clearAuthenticated(): void {
  publish({ status: 'anonymous', user: null, generation: snapshot.generation + 1, contextRevision: sessionContextRevision() })
}

/** Test and hot-reload helper; production code should use clearAuthenticated. */
export function resetAuthState(): void {
  publish({ status: 'unknown', user: null, generation: snapshot.generation + 1, contextRevision: sessionContextRevision() })
}
