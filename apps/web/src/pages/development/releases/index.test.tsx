import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, expect, it, vi } from 'vitest'
import type { ReleaseRecord } from '@/api/workflow'
import ReleasePage from './index'

const api = vi.hoisted(() => ({
  listReleases: vi.fn(), approveRelease: vi.fn(), createRelease: vi.fn(), deleteRelease: vi.fn(),
  publishRelease: vi.fn(), rejectRelease: vi.fn(), rollbackRelease: vi.fn(), updateRelease: vi.fn()
}))
const notices = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
const confirm = vi.hoisted(() => vi.fn())
const access = vi.hoisted(() => ({ canManage: true }))
vi.mock('@/api/workflow', () => api)
vi.mock('@/api/quality', () => ({ listRules: async () => [] }))
vi.mock('@/utils/access', () => ({ canManagePlatform: () => access.canManage }))
vi.mock('@/components/PageHeader', () => ({ default: ({ description, extra }: { description: string; extra: React.ReactNode }) => <div><p>{description}</p>{extra}</div> }))
vi.mock('@/components/PageToolbar', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))
vi.mock('@/components/DataSurface', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))
vi.mock('@/components/RowActions', () => ({ default: ({ items }: { items: { key: string; label: string; onClick: () => void }[] }) =>
  <>{items.map((item) => <button key={item.key} onClick={item.onClick}>{item.label}</button>)}</> }))

const record = (status: ReleaseRecord['status'], id = 1): ReleaseRecord => ({
  id, releaseNo: `REL-${id}`, title: `登记单 ${id}`, releaseType: 'table', targetName: `table_${id}`,
  targetVersion: 'v2', status, approvedDigest: 'a'.repeat(64), releasedAt: '2026-10-26T09:00:00Z'
})
const row = (title: string) => within(screen.getByText(title).closest('tr') as HTMLElement)
type Confirmation = { title: string; content: string; onOk: () => Promise<void> }
const confirmation = () => confirm.mock.calls.at(-1)?.[0] as Confirmation

beforeEach(() => {
  vi.clearAllMocks()
  access.canManage = true
  vi.spyOn(App, 'useApp').mockReturnValue({ message: notices, modal: { confirm } } as unknown as ReturnType<typeof App.useApp>)
})

it('labels stored release and rollback states as registrations rather than actual deployment', async () => {
  api.listReleases.mockResolvedValue([record('released'), record('rolled_back', 2)])
  render(<App><ReleasePage /></App>)
  await screen.findByText('登记单 1')
  expect(row('登记单 1').getByText('已登记发布')).toBeInTheDocument()
  expect(row('登记单 2').getByText('已登记回退')).toBeInTheDocument()
  expect(screen.getByRole('columnheader', { name: '发布登记时间' })).toBeInTheDocument()
  expect(screen.queryByText('已上线')).not.toBeInTheDocument()
  expect(screen.queryByText('上线时间')).not.toBeInTheDocument()
})

it('confirms release registration scope and preserves the actual publish API action', async () => {
  api.listReleases.mockResolvedValueOnce([record('approved')]).mockResolvedValue([record('released')])
  api.publishRelease.mockResolvedValue(record('released'))
  render(<App><ReleasePage /></App>)
  await screen.findByText('登记单 1')
  fireEvent.click(row('登记单 1').getByRole('button', { name: '登记发布' }))
  expect(confirmation().title).toBe('登记发布“登记单 1”？')
  expect(confirmation().content).toBe('确认后更新发布登记状态和时间。下游投放、消费方切换及其验证需在对应系统另行执行。')
  expect(api.publishRelease).not.toHaveBeenCalled()
  await act(async () => { await confirmation().onOk() })
  expect(api.publishRelease).toHaveBeenCalledExactlyOnceWith(1)
  expect(notices.success).toHaveBeenCalledWith('已登记发布')
  expect(row('登记单 1').getByText('已登记发布')).toBeInTheDocument()
})

it('confirms rollback registration without promising physical stop or recovery', async () => {
  api.listReleases.mockResolvedValueOnce([record('released')]).mockResolvedValue([record('rolled_back')])
  api.rollbackRelease.mockResolvedValue(record('rolled_back'))
  render(<App><ReleasePage /></App>)
  await screen.findByText('登记单 1')
  fireEvent.click(row('登记单 1').getByRole('button', { name: '登记回退' }))
  expect(confirmation().title).toBe('登记回退“登记单 1”？')
  expect(confirmation().content).toBe('确认后将此发布单标记为“已登记回退”。下游停用、数据恢复或部署回退需在对应系统另行执行。')
  expect(api.rollbackRelease).not.toHaveBeenCalled()
  await act(async () => { await confirmation().onOk() })
  expect(api.rollbackRelease).toHaveBeenCalledExactlyOnceWith(1)
  expect(notices.success).toHaveBeenCalledWith('已登记回退')
  expect(row('登记单 1').getByText('已登记回退')).toBeInTheDocument()
})

it('retains backend approval failures and never reports a registration success for them', async () => {
  api.listReleases.mockResolvedValue([record('approved')])
  api.publishRelease.mockRejectedValue({ response: { status: 409, data: { message: '发布定义与审批快照不一致，禁止发布' } } })
  render(<App><ReleasePage /></App>)
  await screen.findByText('登记单 1')
  fireEvent.click(row('登记单 1').getByRole('button', { name: '登记发布' }))
  await act(async () => { await confirmation().onOk() })
  expect(notices.error).toHaveBeenCalledWith('发布定义与审批快照不一致，禁止发布')
  expect(notices.success).not.toHaveBeenCalled()
  expect(api.listReleases).toHaveBeenCalledTimes(1)
})

it('keeps a viewer read-only while still describing release states accurately', async () => {
  access.canManage = false
  api.listReleases.mockResolvedValue([record('released')])
  render(<App><ReleasePage /></App>)
  await screen.findByText('登记单 1')
  expect(screen.getByText('只读访问')).toBeInTheDocument()
  expect(row('登记单 1').getByText('已登记发布')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '登记发布' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '登记回退' })).not.toBeInTheDocument()
  expect(api.publishRelease).not.toHaveBeenCalled()
  expect(api.rollbackRelease).not.toHaveBeenCalled()
})
