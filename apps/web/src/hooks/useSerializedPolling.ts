import { useCallback, useEffect, useRef } from 'react'

function reportAutomaticPollingFailure() {
  // Network/apply failures are isolated below; a scheduler failure stops the loop.
  // Report only a fixed category, never response bodies or user data.
  console.warn('Automatic polling stopped after scheduling failure')
}

/** One bounded batch at a time, a coalesced manual refresh, and transport abort on disposal. */
export function useSerializedPolling<T>(load: (signal: AbortSignal) => Promise<T>, apply: (value: T) => void, intervalMs: number) {
  const loadRef = useRef(load)
  const applyRef = useRef(apply)
  const mounted = useRef(false)
  const generation = useRef(0)
  const pending = useRef<Promise<void> | null>(null)
  const queued = useRef(false)
  const controller = useRef<AbortController | null>(null)
  const timer = useRef<number>()
  loadRef.current = load
  applyRef.current = apply

  const refresh = useCallback((queueIfBusy = true): Promise<void> => {
    if (!mounted.current) return Promise.resolve()
    if (pending.current) {
      queued.current ||= queueIfBusy
      return pending.current
    }
    window.clearTimeout(timer.current)
    const requestGeneration = generation.current
    const execute = async () => {
      do {
        queued.current = false
        const request = new AbortController()
        controller.current = request
        try {
          const value = await loadRef.current(request.signal)
          if (mounted.current && generation.current === requestGeneration && !request.signal.aborted) applyRef.current(value)
        } catch {
          // The caller owns per-item error results; aborts and an unavailable whole
          // batch leave the last successful view intact and polling may recover.
        }
      } while (mounted.current && generation.current === requestGeneration && queued.current)
    }
    const current = execute().finally(() => {
      if (pending.current !== current) return
      pending.current = null
      if (mounted.current && generation.current === requestGeneration) {
        timer.current = window.setTimeout(() => { refresh(false).catch(reportAutomaticPollingFailure) }, intervalMs)
      }
    })
    pending.current = current
    return current
  }, [intervalMs])

  useEffect(() => {
    mounted.current = true
    refresh(false).catch(reportAutomaticPollingFailure)
    return () => {
      mounted.current = false
      generation.current += 1
      queued.current = false
      controller.current?.abort()
      pending.current = null
      window.clearTimeout(timer.current)
    }
  }, [refresh])
  return refresh
}
