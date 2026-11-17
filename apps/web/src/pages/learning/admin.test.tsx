import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningAdminPage from './admin'

vi.mock('@/api/learning', () => ({
  createLab: vi.fn(),
  decideApproval: vi.fn(),
  getAdminAnalytics: vi.fn(),
  listAdminDatasets: vi.fn(),
  listAdminLabs: vi.fn(),
  listApprovals: vi.fn(),
  publishLab: vi.fn(),
  registerAdminDataset: vi.fn(),
  reviewLab: vi.fn(),
  unpublishLab: vi.fn(),
  updateLab: vi.fn(),
  verifyAdminDatasets: vi.fn()
}))

const overview: api.AdminOverview = {
  paths: 5, labs: 53, publishedLabs: 53, submissions: 0, gradedSubmissions: 0,
  activeSandboxes: 0, learners: 0, pendingApprovals: 0, activeLearners7d: 0, passRate: 0
}

const dataset: api.DatasetManifestView = {
  code: 'teach.orders_raw', version: 1, tableName: 'teach.orders_raw', rowCount: 26,
  checksum: 'c25a4807', license: 'internal-training', source: 'synthetic',
  piiReviewed: true, status: 'published', note: '合成电商订单脏数据教学集'
}

function page() {
  return render(
    <MemoryRouter>
      <AntdApp><LearningAdminPage /></AntdApp>
    </MemoryRouter>
  )
}

async function openDatasetsTab() {
  fireEvent.click(await screen.findByText('数据样例'))
}

describe('learning admin dataset audit', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listAdminLabs).mockResolvedValue([])
    vi.mocked(api.listApprovals).mockResolvedValue([])
    vi.mocked(api.listAdminDatasets).mockResolvedValue([dataset])
    vi.mocked(api.getAdminAnalytics).mockResolvedValue({ overview, labs: [], events: [], summary: {} })
  })

  it('verifies datasets against their manifests', async () => {
    vi.mocked(api.verifyAdminDatasets).mockResolvedValue([{
      code: 'teach.orders_raw', version: 1, rowCount: 26, expectedRowCount: 26,
      checksum: 'c25a4807', expectedChecksum: 'c25a4807', rowCountMatches: true, checksumMatches: true
    }])
    page()
    await openDatasetsTab()

    fireEvent.click(await screen.findByRole('button', { name: /校验一致性/ }))

    expect(await screen.findByText('一致')).toBeInTheDocument()
    expect(api.verifyAdminDatasets).toHaveBeenCalledTimes(1)
  })

  it('marks checksum drift after verification', async () => {
    vi.mocked(api.verifyAdminDatasets).mockResolvedValue([{
      code: 'teach.orders_raw', version: 1, rowCount: 26, expectedRowCount: 26,
      checksum: 'changed', expectedChecksum: 'c25a4807', rowCountMatches: true, checksumMatches: false
    }])
    page()
    await openDatasetsTab()

    fireEvent.click(await screen.findByRole('button', { name: /校验一致性/ }))

    expect(await screen.findByText('校验和漂移')).toBeInTheDocument()
  })
  it('registers a new dataset version from the admin dialog', async () => {
    vi.mocked(api.registerAdminDataset).mockResolvedValue({ ...dataset, version: 2 })
    page()
    await openDatasetsTab()

    fireEvent.click(await screen.findByRole('button', { name: /登记新版本/ }))
    fireEvent.change(screen.getByPlaceholderText('teach.orders_raw'), { target: { value: 'teach.orders_raw' } })
    fireEvent.click(screen.getByRole('button', { name: /^登\s*记$/ }))

    await waitFor(() => expect(api.registerAdminDataset).toHaveBeenCalledWith(expect.objectContaining({
      code: 'teach.orders_raw',
      tableName: 'teach.orders_raw',
      version: 1,
      license: 'internal-training',
      source: 'real-desensitized',
      piiReviewed: false
    })))
  })
  it('shows the 7-day learning event counts in the analytics tab', async () => {
    vi.mocked(api.getAdminAnalytics).mockResolvedValue({
      overview,
      labs: [],
      events: [{ event: 'portfolio_exported', count: 2 }, { event: 'lab_reset', count: 1 }],
      summary: {}
    })
    page()

    fireEvent.click(await screen.findByText('运营分析'))

    expect(await screen.findByText('portfolio_exported')).toBeInTheDocument()
    expect(screen.getByText('lab_reset')).toBeInTheDocument()
    expect(screen.getByText('2')).toBeInTheDocument()
  })
})