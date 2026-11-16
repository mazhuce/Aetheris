import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/analytics'
import DashboardEditorPage from './editor'

vi.mock('@/api/analytics', () => ({
  getAnalyticsDashboard: vi.fn(),
  listAnalyticsDatasets: vi.fn()
}))
vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: () => true, loading: false }) }))
vi.mock('@/utils/access', () => ({ canManagePlatform: () => true }))
vi.mock('./components/DashboardCanvas', () => ({ default: () => <div data-testid="dashboard-canvas">canvas</div> }))

class TestBroadcastChannel {
  onmessage: ((event: MessageEvent) => void) | null = null
  close() {}
  postMessage() {}
}

const dashboard = {
  id: 7,
  name: '销售看板',
  description: '',
  slug: 'sales',
  status: 'draft' as const,
  draftSpec: { schemaVersion: 1, theme: 'dark' as const, layouts: {}, widgets: [], globalFilters: [] },
  publishedVersion: 0,
  createdBy: 'owner',
  updatedBy: 'owner',
  createdAt: '',
  updatedAt: ''
}

function page() {
  return render(
    <MemoryRouter initialEntries={['/analytics/dashboards/7/edit']}>
      <Routes><Route path="/analytics/dashboards/:id/edit" element={<DashboardEditorPage />} /></Routes>
    </MemoryRouter>
  )
}

describe('dashboard editor request state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.stubGlobal('BroadcastChannel', TestBroadcastChannel)
    vi.mocked(api.listAnalyticsDatasets).mockResolvedValue([])
  })

  it('leaves the editor loading state on a 403 and retries', async () => {
    vi.mocked(api.getAnalyticsDashboard)
      .mockRejectedValueOnce({ response: { status: 403 } })
      .mockResolvedValueOnce(dashboard)
    page()
    expect(await screen.findByRole('alert')).toHaveTextContent('403')
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByTestId('dashboard-canvas')).toBeInTheDocument()
    await waitFor(() => expect(api.getAnalyticsDashboard).toHaveBeenCalledTimes(2))
  })
})
