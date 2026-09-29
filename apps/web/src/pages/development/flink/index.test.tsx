import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { TaskView } from '@/api/workflow'
import FlinkDevelopmentPage from './index'

const api = vi.hoisted(() => ({
  getFlinkHealth: vi.fn(),
  listTasks: vi.fn(),
  runTask: vi.fn()
}))

vi.mock('@/api/workflow', () => api)
vi.mock('@/utils/access', () => ({ canManagePlatform: () => true }))
vi.mock('@/pages/workflow/components/TaskDetailDrawer', () => ({
  default: () => null
}))

function task(name: string, taskType: 'flink' | 'spark'): TaskView {
  return {
    task: { id: taskType === 'flink' ? 1 : 2, name, taskType, flinkMode: taskType === 'flink' ? 'streaming' : undefined },
    upstreamCount: 0
  } as TaskView
}

describe('FlinkDevelopmentPage', () => {
  beforeEach(() => {
    api.getFlinkHealth.mockResolvedValue({ status: 'UP', version: '1.20.1', endpoint: 'http://flink:8083' })
    api.listTasks.mockResolvedValue([task('orders-stream', 'flink'), task('daily-batch', 'spark')])
    api.runTask.mockResolvedValue({ id: 10 })
  })

  it('shows gateway health and filters the task list to Flink tasks', async () => {
    render(<App><FlinkDevelopmentPage /></App>)

    await waitFor(() => expect(api.getFlinkHealth).toHaveBeenCalled())
    expect(screen.getByText('Flink SQL Gateway')).toBeInTheDocument()
    expect(screen.getByText('orders-stream')).toBeInTheDocument()
    expect(screen.queryByText('daily-batch')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: /运行/ })).toBeInTheDocument()
  })
})
