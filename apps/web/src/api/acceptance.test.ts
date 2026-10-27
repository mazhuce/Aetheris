import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import http from './http'
import { getAcceptanceHistory, getCurrentAcceptance, getPlatformReleaseAcceptance } from './acceptance'
import { currentAcceptanceFixture } from '@/pages/development/acceptance/testFixtures'

vi.mock('./http', () => ({ default: { get: vi.fn() } }))
beforeEach(() => { vi.clearAllMocks() })
afterEach(() => { vi.unstubAllGlobals(); vi.useRealTimers() })

it('reads only the authenticated current verifier and passes abort/no-store/deadline', async () => {
  vi.mocked(http.get).mockResolvedValue({ data: currentAcceptanceFixture() })
  const controller = new AbortController()
  expect((await getCurrentAcceptance(controller.signal)).state).toBe('verified')
  expect(http.get).toHaveBeenCalledWith('/api/system/acceptance/current', {
    signal: controller.signal, timeout: 5000, headers: { 'Cache-Control': 'no-store' },
  })
})

it.each([404, 403, 503])('does not fall back to a static passed summary on verifier HTTP %s', async (status) => {
  const error = { response: { status } }
  vi.mocked(http.get).mockRejectedValue(error)
  const fetcher = vi.fn()
  vi.stubGlobal('fetch', fetcher)
  await expect(getCurrentAcceptance()).rejects.toBe(error)
  expect(fetcher).not.toHaveBeenCalled()
})

it('rejects success-shaped invalid wire data before a page can display it', async () => {
  vi.mocked(http.get).mockResolvedValue({ data: { state: 'verified', status: 'passed' } })
  await expect(getCurrentAcceptance()).rejects.toThrow('当前验收响应未满足校验契约')
})

it('keeps old schema1/schema2 summaries readable only through the history channel', async () => {
  const fetcher = vi.fn(async (path: string) => ({ ok: true, json: async () => ({
    schemaVersion: path.includes('control-plane') ? 2 : 1, gate: 'historical', status: 'passed',
    generatedAtUtc: '2026-10-03T10:00:00Z', source: 'old/report.json',
  }) }))
  vi.stubGlobal('fetch', fetcher)
  const history = await getAcceptanceHistory(new AbortController().signal)
  expect(history).toHaveLength(2)
  expect(history.map((item) => item.kind)).toEqual(['platform', 'control-plane'])
  expect(history.every((item) => !('state' in item) && !('currentBinding' in item))).toBe(true)
  expect(http.get).not.toHaveBeenCalled()
})

it('bounds a stalled historical file request and forwards abort without hanging current refresh', async () => {
  vi.useFakeTimers()
  let signal!: AbortSignal
  vi.stubGlobal('fetch', vi.fn((_path: string, config: { signal: AbortSignal }) => new Promise((_, reject) => {
    signal = config.signal
    config.signal.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true })
  })))
  const pending = getPlatformReleaseAcceptance().catch((error: unknown) => error)
  await vi.advanceTimersByTimeAsync(5000)
  expect(signal.aborted).toBe(true)
  expect(await pending).toBeInstanceOf(DOMException)
})
