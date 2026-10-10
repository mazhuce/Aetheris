import { useSyncExternalStore } from 'react'
import { getAuthSnapshot, subscribeAuth } from '@/auth/authState'

export function useAuthSession() {
  return useSyncExternalStore(subscribeAuth, getAuthSnapshot, getAuthSnapshot)
}
