import { act, renderHook, waitFor } from '@testing-library/react'
import { beforeEach, expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import { getMyResourcePermissions } from '@/api/resourcePermissions'
import { useResourceActions } from './useResourceActions'

vi.mock('@/api/resourcePermissions', () => ({ getMyResourcePermissions: vi.fn() }))
beforeEach(() => {
  vi.resetAllMocks()
  setAuthenticated({ id: 11, accessVersion: 0, permissionVersion: 0, username: 'alice', role: 'viewer' })
})

it('shows exactly the granted action and denies failed or unrequested IDs', async () => {
  vi.mocked(getMyResourcePermissions).mockImplementation(async (_, action) => {
    if (action === 'execute') return [1, 99]
    if (action === 'update') return [2]
    throw new Error('auth unavailable')
  })
  const resources = [{ id: 1 }, { id: 2 }]
  const { result } = renderHook(() => useResourceActions('ingestion_task', resources))
  expect(result.current.can('execute', 1)).toBe(false)
  await waitFor(() => expect(result.current.loading).toBe(false))
  expect(result.current.can('execute', 1)).toBe(true)
  expect(result.current.can('execute', 2)).toBe(false)
  expect(result.current.can('update', 2)).toBe(true)
  expect(result.current.can('execute', 99)).toBe(false)
  expect(result.current.can('delete', 1)).toBe(false)
})

it('drops stale permission results after switching subjects or refreshing the list', async () => {
  let resolveOld!: (ids: number[]) => void
  const old = new Promise<number[]>((resolve) => { resolveOld = resolve })
  vi.mocked(getMyResourcePermissions).mockImplementationOnce(() => old).mockResolvedValue([])
  const first = [{ id: 1 }]
  const { result, rerender } = renderHook(({ items }) => useResourceActions('workflow_task', items), { initialProps: { items: first } })
  act(() => setAuthenticated({ id: 12, accessVersion: 0, permissionVersion: 0, username: 'bob', role: 'viewer' }))
  await waitFor(() => expect(result.current.loading).toBe(false))
  await act(async () => { resolveOld([1]); await old })
  expect(result.current.can('execute', 1)).toBe(false)

  vi.mocked(getMyResourcePermissions).mockResolvedValue([1])
  rerender({ items: [{ id: 1 }] })
  expect(result.current.can('execute', 1)).toBe(false)
  await waitFor(() => expect(result.current.can('execute', 1)).toBe(true))
})
