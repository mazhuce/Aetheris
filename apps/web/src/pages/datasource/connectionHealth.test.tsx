import { act, renderHook } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ConnectionHealth, DataSource } from '@/api/metadata'
import { effectiveConnectionStatus } from './connectionHealth'
import { useConnectionHealthClock } from './useConnectionHealthClock'

const checkedAt = '2026-09-07T08:00:00Z'
const expiresAt = '2026-09-07T08:01:00Z'
const health: ConnectionHealth = { status: 'available', checkedAt, lastSuccessAt: checkedAt, expiresAt }

afterEach(() => vi.useRealTimers())

describe('connection health freshness', () => {
  it('requires a fresh probe instead of treating missing health or historical success as available', () => {
    expect(effectiveConnectionStatus(undefined)).toBe('unknown')
    expect(effectiveConnectionStatus({ status: 'unknown', lastSuccessAt: checkedAt })).toBe('unknown')
    expect(effectiveConnectionStatus({ status: 'available', checkedAt })).toBe('unknown')
    expect(effectiveConnectionStatus({ ...health, expiresAt: 'invalid' })).toBe('unknown')
  })

  it('expires successful, failed, and stranded checking results at the server-provided deadline', () => {
    const beforeExpiry = Date.parse(expiresAt) - 1
    for (const status of ['available', 'unavailable', 'checking'] as const) {
      expect(effectiveConnectionStatus({ ...health, status }, beforeExpiry)).toBe(status)
      expect(effectiveConnectionStatus({ ...health, status }, Date.parse(expiresAt))).toBe('unknown')
    }
  })

  it('expires an open page without a refresh or a network probe', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(checkedAt))
    const records: DataSource[] = [{ id: 5, name: 'Spark', type: 'spark', host: 'relay', connectionHealth: health }]
    const { result, unmount } = renderHook(() => effectiveConnectionStatus(health, useConnectionHealthClock(records)))
    expect(result.current).toBe('available')
    act(() => vi.advanceTimersByTime(60_000))
    expect(result.current).toBe('unknown')
    unmount()
    expect(vi.getTimerCount()).toBe(0)
  })
})
