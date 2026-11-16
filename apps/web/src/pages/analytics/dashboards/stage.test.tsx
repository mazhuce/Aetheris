import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { Link, MemoryRouter, Route, Routes } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/analytics'
import { PublishedDashboardPage } from './stage'

vi.mock('@/api/analytics', () => ({
  getAnalyticsDashboard: vi.fn(),
  getPublishedDashboard: vi.fn(),
  listAnalyticsDatasets: vi.fn()
}))
vi.mock('./components/DashboardCanvas', () => ({
  default: ({ dashboardKey }: { dashboardKey?: string | number }) => <div data-testid="dashboard-canvas">canvas:{dashboardKey}</div>
}))

const dashboard = {
  id: 7,
  name: '销售看板',
  description: '',
  slug: 'sales',
  status: 'published' as const,
  draftSpec: { schemaVersion: 1, theme: 'dark' as const, layouts: {}, widgets: [], globalFilters: [] },
  publishedSpec: { schemaVersion: 1, theme: 'dark' as const, layouts: {}, widgets: [], globalFilters: [] },
  publishedVersion: 3,
  createdBy: 'owner',
  updatedBy: 'owner',
  createdAt: '',
  updatedAt: ''
}

function page() {
  return render(
    <MemoryRouter initialEntries={['/d/sales']}>
      <Routes><Route path="/d/:slug" element={<PublishedDashboardPage />} /></Routes>
    </MemoryRouter>
  )
}

describe('published dashboard request state', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    vi.mocked(api.listAnalyticsDatasets).mockResolvedValue([])
  })

  it('leaves loading on a 503 and retries the same dashboard', async () => {
    vi.mocked(api.getPublishedDashboard)
      .mockRejectedValueOnce({ response: { status: 503 } })
      .mockResolvedValueOnce(dashboard)
    page()
    expect(await screen.findByRole('alert')).toHaveTextContent('503')
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByTestId('dashboard-canvas')).toHaveTextContent('published:7:3')
    await waitFor(() => expect(api.getPublishedDashboard).toHaveBeenCalledTimes(2))
  })

  it('exits loading with a retryable 404 state', async () => {
    vi.mocked(api.getPublishedDashboard).mockRejectedValueOnce({ response: { status: 404 } })
    page()
    expect(await screen.findByRole('alert')).toHaveTextContent('404')
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
  })

  it('shows dashboard B after switching from A without reusing A canvas', async () => {
    vi.mocked(api.getPublishedDashboard)
      .mockResolvedValueOnce({ ...dashboard, id: 7, slug: 'a', name: 'A 看板' })
      .mockResolvedValueOnce({ ...dashboard, id: 8, slug: 'b', name: 'B 看板' })
    render(
      <MemoryRouter initialEntries={['/d/a']}>
        <Routes>
          <Route
            path="/d/:slug"
            element={<><PublishedDashboardPage /><Link to="/d/b">切换看板</Link></>}
          />
        </Routes>
      </MemoryRouter>
    )
    expect(await screen.findByTestId('dashboard-canvas')).toHaveTextContent('published:7:3')
    fireEvent.click(screen.getByRole('link', { name: '切换看板' }))
    await waitFor(() => expect(screen.getByTestId('dashboard-canvas')).toHaveTextContent('published:8:3'))
    expect(screen.getByText('B 看板')).toBeInTheDocument()
    expect(screen.queryByText('A 看板')).not.toBeInTheDocument()
  })
})
