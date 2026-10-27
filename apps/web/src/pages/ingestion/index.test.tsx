import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import * as api from '@/api/ingestion'
import { listDataSources } from '@/api/metadata'
import IngestionPage from './index'

vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: (action: string, id: number) => id === 1 && ['execute', 'update'].includes(action), loading: false }) }))
vi.mock('@/api/ingestion', () => ({ listTasks: vi.fn(), runTask: vi.fn(), updateTask: vi.fn(), createTask: vi.fn(), deleteTask: vi.fn(), getTask: vi.fn(), getTaskApproval: vi.fn(), approveTask: vi.fn(), bindTaskCron: vi.fn() }))
vi.mock('@/api/metadata', () => ({ listDataSources: vi.fn() }))
beforeEach(() => {
  vi.clearAllMocks()
  setAuthenticated({ id: 15, accessVersion: 0, permissionVersion: 0, username: 'operator', role: 'viewer' })
  vi.mocked(api.listTasks).mockResolvedValue([1, 2].map((id) => ({ id, name: `接入${id}`, sourceType: 'jdbc', sourceDatasourceId: 3, targetDatasourceId: 4, sourceSql: 'SELECT 1', targetTable: 'warehouse.t', status: 'enabled' })))
  vi.mocked(api.runTask).mockResolvedValue({ id: 8, taskId: 1, status: 'success', rowsWritten: 1, startedAt: '2026-10-18T00:00:00Z' })
})

it('lets a delegated operator run only the granted task and edit only safe fields', async () => {
  render(<App><IngestionPage /></App>)
  fireEvent.click(await screen.findByRole('button', { name: '运行任务 接入1' }))
  await waitFor(() => expect(api.runTask).toHaveBeenCalledWith(1))
  expect(screen.queryByRole('button', { name: '运行任务 接入2' })).not.toBeInTheDocument()
  expect(listDataSources).not.toHaveBeenCalled()
  expect(api.getTaskApproval).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button', { name: '更多操作' }))
  fireEvent.click(await screen.findByText('编辑'))
  const name = await screen.findByLabelText('任务名称')
  expect(screen.getByLabelText('来源 SQL')).toBeDisabled()
  fireEvent.change(name, { target: { value: '更新名称' } })
  fireEvent.submit(name.closest('form')!)
  await waitFor(() => expect(api.updateTask).toHaveBeenCalledWith(1, expect.objectContaining({ name: '更新名称' })))
  expect(vi.mocked(api.updateTask).mock.calls[0][1]).not.toHaveProperty('sourceSql')
  expect(vi.mocked(api.updateTask).mock.calls[0][1]).not.toHaveProperty('targetDatasourceId')
})

it('opens approval review on the selected resource and reloads it after a delegated cron save', async () => {
  const approval = { executionRevision: 4, definitionSha256: 'a'.repeat(64), approved: false, scheduleGeneration: 9, bindingGeneration: 0, cronBound: false }
  vi.mocked(api.getTaskApproval).mockResolvedValue(approval)
  vi.mocked(api.getTask).mockResolvedValue({ id: 1, name: '接入1', sourceType: 'jdbc', sourceDatasourceId: 3, targetDatasourceId: 4, sourceSql: 'SELECT 1', targetTable: 'warehouse.t', status: 'enabled' })
  vi.mocked(api.updateTask).mockResolvedValue({ id: 1, name: '接入1', sourceType: 'jdbc', targetTable: 'warehouse.t' })
  render(<App><IngestionPage /></App>); fireEvent.click(await screen.findByRole('button', { name: '查看执行批准 接入1' }))
  expect(await screen.findByText('未批准')).toBeInTheDocument(); expect(api.getTaskApproval).toHaveBeenCalledTimes(2)
  fireEvent.click(screen.getByRole('button', { name: '更多操作' })); fireEvent.click(await screen.findByText('编辑'))
  const cron = await screen.findByLabelText('定时调度'); fireEvent.change(cron, { target: { value: '0 0 3 * * ?' } }); fireEvent.submit(cron.closest('form')!)
  await waitFor(() => expect(api.updateTask).toHaveBeenCalled()); await waitFor(() => expect(api.getTaskApproval).toHaveBeenCalledTimes(4))
  expect(api.approveTask).not.toHaveBeenCalled(); expect(api.bindTaskCron).not.toHaveBeenCalled(); expect(listDataSources).not.toHaveBeenCalled()
})
