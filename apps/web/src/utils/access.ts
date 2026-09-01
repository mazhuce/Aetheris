import type { UserRole } from '@/api/auth'
import { ROLE_KEY } from '@/api/http'

export function getCurrentRole(): UserRole {
  return localStorage.getItem(ROLE_KEY) === 'admin' ? 'admin' : 'viewer'
}

export function canManagePlatform(): boolean {
  return getCurrentRole() === 'admin'
}
