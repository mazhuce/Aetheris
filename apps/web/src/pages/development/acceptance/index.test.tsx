import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AcceptanceProgressPage from './index'

const api = vi.hoisted(() => ({
  listDataSources: vi.fn(),
  listTasks: vi.fn(),
  getFlinkHealth: vi.fn(),
  getSparkHealth: vi.fn()
}))

vi.mock('@/api/metadata', () => api)
vi.mock('@/api/workflow', () => api)

describe('AcceptanceProgressPage', () => {
  beforeEach(() => {
    api.listDataSources.mockResolvedValue([
      { id: 1, name: 'orders', type: 'postgresql', connectionHealth: { status: 'available' } },
      { id: 2, name: 'events', type: 'mysql', connectionHealth: { status: 'unknown' } }
    ])
    api.listTasks.mockResolvedValue([
      { task: { id: 1, name: 'orders-stream', taskType: 'flink' }, lastStatus: 'running', upstreamCount: 0 },
      { task: { id: 2, name: 'unknown-stream', taskType: 'flink' }, lastStatus: 'unknown', upstreamCount: 0 }
    ])
    api.getFlinkHealth.mockResolvedValue({ status: 'UP', version: '1.20.1' })
    api.getSparkHealth.mockResolvedValue({ status: 'UP', activeJobs: 1, maxConcurrency: 4 })
  })

  it('shows milestone progress and live service/source tracking', async () => {
    render(<App><AcceptanceProgressPage /></App>)

    await waitFor(() => expect(api.getFlinkHealth).toHaveBeenCalled())
    expect(screen.getByLabelText('R1-R5 阶段进度')).toBeInTheDocument()
    expect(screen.getByText('实时湖仓兼容矩阵')).toBeInTheDocument()
    expect(screen.getByText('Datasource 七类运行门禁最近一次已通过')).toBeInTheDocument()
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument()
    expect(screen.getByText('九类外部数据源门禁仍待真实实例')).toBeInTheDocument()
    expect(screen.getByText('Flink 1.20.1')).toBeInTheDocument()
    expect(screen.getByText('未知 1 · 失败 0')).toBeInTheDocument()
  })
})
