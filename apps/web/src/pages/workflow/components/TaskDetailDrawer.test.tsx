import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { expect, it, vi } from 'vitest'
import { getTask, listInstances, listDependencies, type TaskView } from '@/api/workflow'
import { setAuthenticated } from '@/auth/authState'
import TaskDetailDrawer from './TaskDetailDrawer'

vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: () => false, loading: false }) }))
vi.mock('@/api/workflow', () => ({
  getTask: vi.fn(), listInstances: vi.fn(), listDependencies: vi.fn(), listInstanceLogs: vi.fn(), listExecutionAttempts: vi.fn(),
  addDependency: vi.fn(), removeDependency: vi.fn(), pauseFlink: vi.fn(), resumeFlink: vi.fn(), upgradeFlink: vi.fn(), listTasks: vi.fn(),
  INSTANCE_STATUS_META: {}, ATTEMPT_STATUS_META: {}
}))
it('ignores the old task response and hides mutation controls for a read-only viewer', async () => {
  setAuthenticated({ id: 16, accessVersion: 0, permissionVersion: 0, username: 'reader', role: 'viewer' })
  let finishOld!: (view: TaskView) => void
  const old = new Promise<TaskView>((resolve) => { finishOld = resolve })
  const view = (id: number, name: string) => ({ task: { id, name, taskType: 'sql', taskSql: 'SELECT 1' } }) as TaskView
  vi.mocked(getTask).mockImplementation((id) => id === 1 ? old : Promise.resolve(view(2, '当前任务B')))
  vi.mocked(listInstances).mockResolvedValue([])
  vi.mocked(listDependencies).mockResolvedValue([{ id: 3, taskId: 2, upstreamTaskId: 8 }])
  const props = { onClose: vi.fn(), onChanged: vi.fn() }
  const { rerender } = render(<App><TaskDetailDrawer {...props} taskId={1} /></App>)
  rerender(<App><TaskDetailDrawer {...props} taskId={2} /></App>)
  await waitFor(() => expect(screen.getAllByText('当前任务B').length).toBeGreaterThan(0))
  await act(async () => { finishOld(view(1, '旧任务A')); await old })
  expect(screen.queryByText('旧任务A')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('tab', { name: /上游依赖/ }))
  expect(screen.queryByRole('button', { name: '添加依赖' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '移除' })).not.toBeInTheDocument()
})
