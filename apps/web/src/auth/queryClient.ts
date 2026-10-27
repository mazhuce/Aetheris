import { QueryClient } from '@tanstack/react-query'
import { getAuthSnapshot, subscribeAuth } from './authState'

export function bindSessionCache(client: QueryClient): () => void {
  let generation = getAuthSnapshot().generation
  return subscribeAuth(() => {
    const next = getAuthSnapshot().generation
    if (next === generation) return
    generation = next
    // Cancel before removing entries, including transports which finish after abort.
    client.cancelQueries().catch(() => undefined)
    client.clear()
  })
}

export const queryClient = new QueryClient({
  defaultOptions: { queries: {
    staleTime: 10_000,
    retry: (count, error) => count < 1 && !('code' in error &&
      ['SESSION_CHANGED', 'SESSION_UNAVAILABLE', 'SESSION_RETRY_REQUIRED', 'ERR_CANCELED'].includes(String(error.code))),
    refetchOnWindowFocus: false
  } }
})
bindSessionCache(queryClient)
