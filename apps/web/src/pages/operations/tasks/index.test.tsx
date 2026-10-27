import { render, screen, within } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, expect, it, vi } from 'vitest'
import * as workflow from '@/api/workflow'
import type { TaskInstance, TaskInstanceStatus } from '@/api/workflow'
import TaskMonitorPage from './index'

vi.mock('@/api/workflow', async (original) => ({
  ...await original<typeof import('@/api/workflow')>(), listAllInstances: vi.fn(), listTasks: vi.fn()
}))
vi.mock('@/hooks/useResponsivePageSize', () => ({ useResponsivePageSize: () => 20 }))
vi.mock('@/components/PageHeader', () => ({ default: ({ description }: { description: string }) => <p>{description}</p> }))
vi.mock('@/components/PageToolbar', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))
vi.mock('@/components/DataSurface', () => ({ default: ({ children, description }: { children: React.ReactNode; description: string }) => <div><p>{description}</p>{children}</div> }))

const rows = (...statuses: TaskInstanceStatus[]): TaskInstance[] => statuses.map((status, index) => ({
  id: index + 1, taskId: index + 1, bizDate: '2026-10-26', status, durationMs: 100
}))
const card = (title: string) => within(screen.getByText(title).closest('.stat-card') as HTMLElement)

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(workflow.listTasks).mockResolvedValue([])
})

it('counts all timeouts as known unsuccessful completions and never reports 100 percent', async () => {
  vi.mocked(workflow.listAllInstances).mockResolvedValue(rows('timed_out', 'timed_out'))
  render(<App><TaskMonitorPage /></App>)
  await screen.findByText('任务 1')
  expect(card('已知完成成功率').getByText('0%')).toBeInTheDocument()
  expect(card('失败及超时实例').getByText('2')).toBeInTheDocument()
  expect(card('失败及超时实例').getByText('失败 0 · 超时 2')).toBeInTheDocument()
  expect(screen.queryByText('100%')).not.toBeInTheDocument()
  expect(workflow.listAllInstances).toHaveBeenCalledWith(undefined, 100)
})

it('shows no completed sample when every instance is unknown or still running', async () => {
  vi.mocked(workflow.listAllInstances).mockResolvedValue(rows('unknown', 'running', 'queued'))
  render(<App><TaskMonitorPage /></App>)
  await screen.findByText('任务 1')
  expect(card('已知完成成功率').getByText('无样本')).toBeInTheDocument()
  expect(screen.getByText('结果待核对 1')).toBeInTheDocument()
  expect(screen.getByText('处理中 2')).toBeInTheDocument()
  expect(screen.queryByText('100%')).not.toBeInTheDocument()
})

it('excludes cancellations skips and unresolved work from the explicit completion denominator', async () => {
  vi.mocked(workflow.listAllInstances).mockResolvedValue(rows('success', 'success', 'failed', 'timed_out', 'cancelled', 'skipped', 'unknown', 'running'))
  render(<App><TaskMonitorPage /></App>)
  await screen.findByText('任务 1')
  expect(card('已知完成成功率').getByText('50%')).toBeInTheDocument()
  expect(card('已知完成成功率').getByText('成功 /（成功 + 失败 + 超时）')).toBeInTheDocument()
  expect(screen.getByText('已取消 1')).toBeInTheDocument()
  expect(screen.getByText('已跳过 1')).toBeInTheDocument()
  expect(screen.getByText('当前筛选返回的最多 100 条可见实例')).toBeInTheDocument()
})
