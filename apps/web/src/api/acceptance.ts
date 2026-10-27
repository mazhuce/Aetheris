import http from './http'
import { readCurrentAcceptance, readHistoricalAcceptance, type CurrentAcceptance, type HistoricalAcceptance } from './acceptanceContract'

export * from './acceptanceContract'

/** Only the authenticated server verifier can establish current acceptance. */
export async function getCurrentAcceptance(signal?: AbortSignal): Promise<CurrentAcceptance> {
  const response = await http.get<unknown>('/api/system/acceptance/current', {
    signal, timeout: 5000, headers: { 'Cache-Control': 'no-store' },
  })
  return readCurrentAcceptance(response.data)
}

async function history(path: string, kind: HistoricalAcceptance['kind'], signal?: AbortSignal): Promise<HistoricalAcceptance> {
  const controller = new AbortController()
  const abort = () => controller.abort()
  if (signal?.aborted) abort()
  signal?.addEventListener('abort', abort, { once: true })
  const timer = setTimeout(abort, 5000)
  try {
    const response = await fetch(path, { cache: 'no-store', signal: controller.signal })
    if (!response.ok) throw new Error('历史验收摘要暂不可用')
    return readHistoricalAcceptance(await response.json(), kind)
  } finally {
    clearTimeout(timer)
    signal?.removeEventListener('abort', abort)
  }
}

/** Compatibility names are historical readers; neither grants current success. */
export function getControlPlaneAcceptance(signal?: AbortSignal): Promise<HistoricalAcceptance> {
  return history('/acceptance/r4-r5-control-plane.json', 'control-plane', signal)
}
export function getPlatformReleaseAcceptance(signal?: AbortSignal): Promise<HistoricalAcceptance> {
  return history('/acceptance/platform-release.json', 'platform', signal)
}
export async function getAcceptanceHistory(signal?: AbortSignal): Promise<HistoricalAcceptance[]> {
  const results = await Promise.allSettled([getPlatformReleaseAcceptance(signal), getControlPlaneAcceptance(signal)])
  return results.flatMap((result) => result.status === 'fulfilled' ? [result.value] : [])
}
