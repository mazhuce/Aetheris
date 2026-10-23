import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import * as api from '@/api/analytics'
import * as metadata from '@/api/metadata'
import AnalyticsDatasetPage from './index'

vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: (action: string, id: number) => action === 'query' && id === 1, loading: false }) }))
vi.mock('@/api/analytics', () => ({ listAnalyticsDatasets: vi.fn(), queryAnalyticsDataset: vi.fn(), createAnalyticsDataset: vi.fn(), updateAnalyticsDataset: vi.fn(), publishAnalyticsDataset: vi.fn(), deleteAnalyticsDataset: vi.fn() }))
vi.mock('@/api/metadata', () => ({ listDataSources: vi.fn(), listPublishedMetricsPage: vi.fn(), listSemanticDimensions: vi.fn() }))
it('offers only the explicitly granted dataset query without exposing publishing or editing', async () => {
  setAuthenticated({ username: 'reader', role: 'viewer' })
  vi.mocked(api.listAnalyticsDatasets).mockResolvedValue([1, 2].map((id) => ({ id, name: `数据${id}`, sourceType: 'sql', status: 'published', createdBy: 'admin', createdAt: '', updatedAt: '', refreshSeconds: 30 })))
  vi.mocked(metadata.listDataSources).mockResolvedValue([])
  vi.mocked(metadata.listPublishedMetricsPage).mockResolvedValue({ items: [], nextCursor: null, hasMore: false })
  vi.mocked(metadata.listSemanticDimensions).mockResolvedValue([])
  vi.mocked(api.queryAnalyticsDataset).mockResolvedValue({ columns: ['amount'], rows: [[12]], rowCount: 1 })
  render(<AnalyticsDatasetPage />)
  fireEvent.click(await screen.findByRole('button', { name: '预览数据集 数据1' }))
  await waitFor(() => expect(api.queryAnalyticsDataset).toHaveBeenCalledWith(1, true))
  expect(screen.queryByRole('button', { name: '预览数据集 数据2' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '编辑' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '新建数据集' })).not.toBeInTheDocument()
  expect(await screen.findByText('12')).toBeInTheDocument()
})
