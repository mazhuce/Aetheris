import { render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LakehouseControlPlanePage from './index'

const api = vi.hoisted(() => ({
  listLakehouseTables: vi.fn(),
  listLakehouseSnapshots: vi.fn(),
  listLakehouseSnapshotRefs: vi.fn(),
  listLakehouseMaintenanceJobs: vi.fn()
}))

vi.mock('@/api/metadata', () => api)

describe('LakehouseControlPlanePage', () => {
  beforeEach(() => {
    api.listLakehouseTables.mockResolvedValue([{
      id: 7,
      catalogName: 'lakehouse',
      namespaceName: 'bronze',
      tableName: 'orders',
      formatVersion: 2,
      layer: 'bronze',
      status: 'active',
      currentSnapshotId: '1002',
      currentSchemaVersion: 3,
      retentionDays: 30
    }])
    api.listLakehouseSnapshots.mockResolvedValue([{
      id: 1,
      lakehouseTableId: 7,
      snapshotId: '1002',
      operation: 'append',
      committedAt: '2026-10-02T04:00:00Z',
      rowCount: 5,
      fileCount: 1,
      schemaVersion: 3,
      createdBy: 'test'
    }])
    api.listLakehouseSnapshotRefs.mockResolvedValue([{
      id: 1,
      lakehouseTableId: 7,
      refName: 'main',
      refType: 'branch',
      snapshotId: '1002',
      protected: false,
      createdBy: 'test',
      updatedBy: 'test'
    }])
    api.listLakehouseMaintenanceJobs.mockResolvedValue([])
  })

  it('shows table, snapshots, refs and maintenance controls', async () => {
    render(<App><LakehouseControlPlanePage /></App>)
    await waitFor(() => expect(api.listLakehouseTables).toHaveBeenCalled())
    expect(screen.getAllByLabelText('湖仓表').length).toBeGreaterThan(0)
    expect(screen.getByText('Iceberg v2')).toBeInTheDocument()
    expect(screen.getByText('main')).toBeInTheDocument()
    expect(screen.getByText('新建分支 / 标签')).toBeInTheDocument()
    expect(screen.getByText('操作边界')).toBeInTheDocument()
  })
})