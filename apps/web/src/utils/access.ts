import type { UserRole } from '@/api/auth'
import { getAuthSnapshot } from '@/auth/authState'

export function getCurrentRole(): UserRole {
  const snapshot = getAuthSnapshot()
  return snapshot.status === 'authenticated' && snapshot.user.role === 'admin' ? 'admin' : 'viewer'
}

export function canManagePlatform(): boolean {
  return getCurrentRole() === 'admin'
}
