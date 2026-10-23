import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { Mock } from 'vitest'
import http from './http'
import { acknowledgeAlert, acknowledgeAlertsBatch, markAllNotificationsRead, markNotificationRead } from './system'

vi.mock('./http', () => ({ default: { put: vi.fn() } }))
const mockedHttp = http as unknown as { put: Mock }

describe('notification and operations acknowledgement contracts', () => {
  beforeEach(() => vi.resetAllMocks())

  it('keeps personal marking separate from shared operations acknowledgements', async () => {
    mockedHttp.put.mockResolvedValue({ data: { acknowledged: 1, hasMore: false } })
    await markNotificationRead(9)
    await markAllNotificationsRead()
    await expect(acknowledgeAlert(17)).resolves.toEqual({ acknowledged: 1, hasMore: false })
    expect(http.put).toHaveBeenNthCalledWith(1, '/api/system/notifications/9/read')
    expect(http.put).toHaveBeenNthCalledWith(2, '/api/system/notifications/read-all')
    expect(http.put).toHaveBeenNthCalledWith(3, '/api/system/alerts/17/acknowledge')
  })

  it('returns one bounded batch result without automatically repeating hasMore', async () => {
    mockedHttp.put.mockResolvedValue({ data: { acknowledged: 500, hasMore: true } })
    await expect(acknowledgeAlertsBatch()).resolves.toEqual({ acknowledged: 500, hasMore: true })
    expect(http.put).toHaveBeenCalledExactlyOnceWith('/api/system/alerts/acknowledge-all')
  })

  it('rejects impossible acknowledgements and propagates dependency failure without a personal fallback', async () => {
    for (const data of [{ acknowledged: 501, hasMore: false }, { acknowledged: -1, hasMore: false }, { acknowledged: 1.5, hasMore: false }, { acknowledged: 1, hasMore: 'false' }, null]) {
      mockedHttp.put.mockResolvedValueOnce({ data })
      await expect(acknowledgeAlertsBatch()).rejects.toThrow('告警确认结果无效')
    }
    mockedHttp.put.mockResolvedValueOnce({ data: { acknowledged: 2, hasMore: false } })
    await expect(acknowledgeAlert(17)).rejects.toThrow('告警确认结果无效')
    mockedHttp.put.mockRejectedValueOnce(new Error('service unavailable'))
    await expect(acknowledgeAlertsBatch()).rejects.toThrow('service unavailable')
    expect(mockedHttp.put.mock.calls.every(([path]) => String(path).startsWith('/api/system/alerts/'))).toBe(true)
  })
})
