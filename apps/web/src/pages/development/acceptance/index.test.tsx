import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { afterEach, beforeEach, expect, it, vi } from 'vitest'
import AcceptanceProgressPage from './index'
import { readCurrentAcceptance } from '@/api/acceptanceContract'
import { listDataSources, type DataSource } from '@/api/metadata'
import StatusBadge from '@/components/StatusBadge'
import { currentAcceptanceFixture } from './testFixtures'

const api = vi.hoisted(() => ({ current: vi.fn(), platform: vi.fn(), control: vi.fn(), history: vi.fn() }))
vi.mock('@/api/acceptance', async () => ({
  ...await vi.importActual('@/api/acceptanceContract'),
  getCurrentAcceptance: api.current,
  getPlatformReleaseAcceptance: api.platform,
  getControlPlaneAcceptance: api.control,
  getAcceptanceHistory: api.history,
}))
vi.mock('@/api/metadata', () => ({ listDataSources: vi.fn(async () => []) }))
vi.mock('@/components/StatusBadge', () => ({ default: vi.fn(({ children }: { children: ReactNode }) => <span>{children}</span>) }))
vi.mock('@/api/workflow', () => ({
  getFlinkHealth: vi.fn(async () => ({ status: 'UP' })),
  getSparkHealth: vi.fn(async () => ({ status: 'UP', activeJobs: 0, maxConcurrency: 1 })),
  listTasks: vi.fn(async () => []),
}))

const legacy = {
  schemaVersion: 1, gate: 'platform-release-acceptance', status: 'passed',
  generatedAtUtc: '2026-10-03T05:33:54Z', source: 'artifacts/old/report.json',
  milestones: ['R1', 'R2', 'R3', 'R4', 'R5'].map((code) => ({ code, status: 'passed' })),
  datasourceNineStatus: 'passed', externalDatasourceStatus: 'missing', dockerStatus: 'UP', manualReviewRequired: [],
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(listDataSources).mockResolvedValue([])
  api.current.mockRejectedValue({ response: { status: 404 } })
  api.platform.mockResolvedValue(legacy)
  api.control.mockRejectedValue({ response: { status: 404 } })
  api.history.mockResolvedValue([{ kind: 'platform', ...legacy }])
})
afterEach(() => { vi.useRealTimers() })

it('never claims nine-source success when the current verifier is unavailable', async () => {
  api.platform.mockRejectedValue({ response: { status: 404 } })
  render(<AcceptanceProgressPage />)
  await screen.findAllByText('在线')
  expect(screen.queryByText('Datasource 本机九类运行门禁最近一次已通过')).not.toBeInTheDocument()
  expect(screen.queryAllByText('已通过')).toHaveLength(0)
})

it('does not count a historical schema1 passed summary as current acceptance', async () => {
  render(<AcceptanceProgressPage />)
  await screen.findAllByText('在线')
  expect(screen.queryAllByText('已通过')).toHaveLength(0)
  expect(screen.getByText('历史运行记录（不计当前验收）')).toBeInTheDocument()
})

it('shows current success only for a complete valid server-verifier result', async () => {
  api.current.mockResolvedValue(readCurrentAcceptance(currentAcceptanceFixture()))
  render(<AcceptanceProgressPage />)
  expect(await screen.findByText('当前冻结版本验收通过')).toBeInTheDocument()
  expect(screen.getAllByText('已通过')).toHaveLength(5)
  expect(screen.getAllByText('当前通过')).toHaveLength(9)
  expect(screen.getByText('历史运行记录（不计当前验收）')).toBeInTheDocument()
})

it.each([404, 403, 503])('keeps a %s current-verifier response unverified even beside old success', async (status) => {
  api.current.mockRejectedValue({ response: { status } })
  render(<AcceptanceProgressPage />)
  await screen.findAllByText('在线')
  expect(screen.getByText('当前验收未验证')).toBeInTheDocument()
  expect(screen.queryByText('当前冻结版本验收通过')).not.toBeInTheDocument()
  expect(screen.queryAllByText('当前通过')).toHaveLength(0)
})

it('expires a valid result while a newer request is still pending', async () => {
  vi.useFakeTimers()
  const now = Date.now()
  api.current.mockResolvedValue(readCurrentAcceptance(currentAcceptanceFixture(now, 1000), now))
  render(<AcceptanceProgressPage />)
  await act(async () => { await Promise.resolve() })
  expect(screen.getByText('当前冻结版本验收通过')).toBeInTheDocument()
  api.current.mockImplementation(() => new Promise(() => undefined))
  fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
  await act(async () => { await vi.advanceTimersByTimeAsync(1001) })
  expect(screen.getByText('当前验收证据已过期')).toBeInTheDocument()
  expect(screen.queryAllByText('当前通过')).toHaveLength(0)
})

it('never paints a verified response that expired while another batch request was pending', async () => {
  vi.useFakeTimers()
  const now = Date.now()
  let releaseSources!: (rows: DataSource[]) => void
  vi.mocked(listDataSources).mockImplementation(() => new Promise((resolve) => { releaseSources = resolve }))
  api.current.mockResolvedValue(readCurrentAcceptance(currentAcceptanceFixture(now, 1000), now))
  render(<AcceptanceProgressPage />)
  await act(async () => { await vi.advanceTimersByTimeAsync(1001); releaseSources([]) })
  expect(screen.getByText('当前验收证据已过期')).toBeInTheDocument()
  expect(vi.mocked(StatusBadge).mock.calls.some(([props]) => props.children === '已通过')).toBe(false)
})

it('coalesces refresh and aborts current/history requests on unmount', async () => {
  let resolveCurrent!: (value: ReturnType<typeof readCurrentAcceptance>) => void
  api.current.mockImplementation(() => new Promise((resolve) => { resolveCurrent = resolve }))
  const view = render(<AcceptanceProgressPage />)
  await waitFor(() => expect(api.current).toHaveBeenCalledTimes(1))
  fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
  expect(api.current).toHaveBeenCalledTimes(1)
  const currentSignal = api.current.mock.calls[0][0] as AbortSignal
  const historySignal = api.history.mock.calls[0][0] as AbortSignal
  view.unmount()
  expect(currentSignal.aborted).toBe(true)
  expect(historySignal.aborted).toBe(true)
  await act(async () => { resolveCurrent(readCurrentAcceptance(currentAcceptanceFixture())) })
  expect(api.current).toHaveBeenCalledTimes(1)
})
