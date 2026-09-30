import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AcceptanceProgressPage from './index'

const api = vi.hoisted(() => ({
  listDataSources: vi.fn(),
  listTasks: vi.fn(),
  getFlinkHealth: vi.fn(),
  getSparkHealth: vi.fn(),
  getControlPlaneAcceptance: vi.fn(),
  getPlatformReleaseAcceptance: vi.fn()
}))

vi.mock('@/api/metadata', () => api)
vi.mock('@/api/workflow', () => api)
vi.mock('@/api/acceptance', () => ({ getControlPlaneAcceptance: api.getControlPlaneAcceptance, getPlatformReleaseAcceptance: api.getPlatformReleaseAcceptance }))

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
api.getPlatformReleaseAcceptance.mockResolvedValue({
      schemaVersion: 1,
      gate: 'platform-release-acceptance',
      status: 'passed',
      generatedAtUtc: '2026-09-30T10:19:06Z',
      source: 'artifacts/platform-release/platform-release-acceptance-report.json',
      milestones: [
        { code: 'R1', status: 'passed' },
        { code: 'R2', status: 'passed' },
        { code: 'R3', status: 'passed' },
        { code: 'R4', status: 'passed' },
        { code: 'R5', status: 'passed' }
      ],
      datasourceNineStatus: 'passed',
      dockerStatus: 'available',
      manualReviewRequired: []
    })
    api.getControlPlaneAcceptance.mockResolvedValue({
      schemaVersion: 1,
      gate: 'R4-R5-lakehouse-control-plane-runtime-gate',
      status: 'passed',
      generatedAtUtc: '2026-09-30T09:45:26Z',
      source: 'artifacts/r4-r5-control-plane/runtime-acceptance-report.json',
      quality: { blockedOnFailure: true, reopenedAfterPass: true },
      schemaPolicy: {
        rename: { decision: 'approval_required', status: 'approved', readyForExecution: true, destructive: true },
        drop: { decision: 'approval_required', status: 'rejected', planRejected: true },
        narrow: { decision: 'isolate', status: 'isolated', planRejected: true }
      }
    })
  })

  it('shows milestone progress and live service/source tracking', async () => {
    render(<App><AcceptanceProgressPage /></App>)

    await waitFor(() => expect(api.getFlinkHealth).toHaveBeenCalled())
    expect(screen.getByLabelText('R1-R5 阶段进度')).toBeInTheDocument()
    expect(screen.getByText('实时湖仓兼容矩阵')).toBeInTheDocument()
    expect(screen.getByText('Datasource 本机九类运行门禁最近一次已通过')).toBeInTheDocument()
    expect(screen.getByText('PostgreSQL')).toBeInTheDocument()
    expect(screen.getByText('三类外部数据源作为补充环境验收')).toBeInTheDocument()
    expect(screen.getByText('Schema policy 运行证据 · 已通过')).toBeInTheDocument()
    expect(screen.getByText(/重命名：审批通过，可生成计划/)).toBeInTheDocument()
    expect(screen.getByText('Flink 1.20.1')).toBeInTheDocument()
    expect(screen.getByText('未知 1 · 失败 0')).toBeInTheDocument()
  })
})
