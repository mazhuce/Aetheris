import { act, render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import http from './http'
import { clearAuthenticated, getAuthSnapshot, setAuthenticated } from '@/auth/authState'
import DashboardWidgetContent from '@/pages/analytics/dashboards/components/DashboardWidgetContent'
import { queryAnalyticsDataset, type DashboardWidget } from './analytics'

vi.mock('./analytics', () => ({ queryAnalyticsDataset: vi.fn() }))
vi.mock('@/contexts/PreferencesContext', () => ({ usePreferences: () => ({ colorMode: 'light', reduceMotion: true }) }))
vi.mock('@/pages/analytics/dashboards/components/EChart', () => ({ default: () => null }))

const alice = { id: 11, username: 'alice', role: 'viewer' as const, accessVersion: 2, permissionVersion: 3 }
const bob = { ...alice, id: 12, username: 'bob' }
const originalAdapter = http.defaults.adapter
const widget = { id: '1', type: 'metric', datasetId: 7, mapping: {}, title: 'Revenue' } as DashboardWidget
function ok(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { data, status: 200, statusText: 'OK', headers: {}, config }
}
function unauthorized(config: InternalAxiosRequestConfig) {
  return Promise.reject(Object.assign(new Error('Unauthorized'), {
    config, response: { status: 401, data: null, headers: {}, config }
  }))
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
afterEach(() => { http.defaults.adapter = originalAdapter; vi.clearAllMocks() })

describe('session boundary regressions', () => {
  it.each(['post', 'put', 'patch', 'delete'] as const)('%s 401 never automatically repeats a mutation', async (method) => {
    setAuthenticated(alice)
    let writes = 0
    http.defaults.adapter = async (config) => {
      if (config.url === '/api/auth/refresh' || config.url === '/api/auth/me') return ok(config, alice)
      return ++writes === 1 ? unauthorized(config) : ok(config, { written: true })
    }
    await expect(http.request({ method, url: '/api/resource/7', data: { value: 1 } })).rejects.toBeDefined()
    expect(writes).toBe(1)
  })

  it('a GET cannot be replayed under another stable UID after refresh', async () => {
    setAuthenticated(alice)
    let reads = 0
    http.defaults.adapter = async (config) => {
      if (config.url === '/api/auth/refresh' || config.url === '/api/auth/me') return ok(config, bob)
      return ++reads === 1 ? unauthorized(config) : ok(config, { secret: 'B' })
    }
    await expect(http.get('/api/private/7')).rejects.toBeDefined()
    expect(reads).toBe(1)
    expect(getAuthSnapshot().user?.username).toBe('bob')
  })

  it('late successful responses cannot enter the next session', async () => {
    setAuthenticated(alice)
    const pending = deferred<AxiosResponse>()
    let sent!: InternalAxiosRequestConfig
    http.defaults.adapter = (config) => { sent = config; return pending.promise }
    const result = http.get('/api/private/7')
    const rejected = expect(result).rejects.toBeDefined()
    await waitFor(() => expect(sent).toBeDefined())
    clearAuthenticated()
    setAuthenticated(bob)
    pending.resolve(ok(sent, { secret: 'A' }))
    await rejected
  })

  it.each([
    ['account switch', bob],
    ['recreated username', { ...alice, id: 99 }],
    ['permission revocation', { ...alice, permissionVersion: 4 }],
    ['password change', { ...alice, accessVersion: 3 }]
  ])('dashboard caches are isolated after %s', async (_label, nextUser) => {
    setAuthenticated(alice)
    const query = vi.mocked(queryAnalyticsDataset)
    query.mockResolvedValueOnce({ columns: ['value'], rows: [['A-private']] } as never)
      .mockResolvedValueOnce({ columns: ['value'], rows: [['B-current']] } as never)
    const client = new QueryClient({ defaultOptions: { queries: { staleTime: 60_000, retry: false } } })
    const view = render(<QueryClientProvider client={client}><DashboardWidgetContent widget={widget} datasets={[]} /></QueryClientProvider>)
    await screen.findByText('A-private')
    act(() => { setAuthenticated(nextUser) })
    await screen.findByText('B-current')
    expect(screen.queryByText('A-private')).not.toBeInTheDocument()
    expect(query).toHaveBeenCalledTimes(2)
    view.unmount()
    client.clear()
  })
})
