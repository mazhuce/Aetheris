import axios from 'axios'
import { clearAuthenticated, setAuthenticated } from '@/auth/authState'
import { coordinateRefresh, sessionRevision, waitForConcurrentRefresh } from '@/auth/refreshCoordinator'

type RetryableConfig = { _aetherisRetried?: boolean; _aetherisSessionProbe?: boolean; _aetherisRevision?: string }
type SessionUser = { username: string; nickname?: string; role: 'admin' | 'viewer' }

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
  withCredentials: true
})

let refreshPromise: Promise<void> | null = null

http.interceptors.request.use((config) => {
  const state = config as typeof config & RetryableConfig
  state._aetherisRevision ??= sessionRevision()
  return config
})

function refreshSession(observed: string): Promise<void> {
  if (!refreshPromise) {
    refreshPromise = coordinateRefresh(observed, async () => {
      const response = await http.post<SessionUser>('/api/auth/refresh')
      setAuthenticated(response.data)
    }).finally(() => {
      refreshPromise = null
    })
  }
  return refreshPromise
}

http.interceptors.response.use(
  (response) => response,
  async (error) => {
    if (error.response?.status === 401) {
      const config = error.config as (typeof error.config & RetryableConfig) | undefined
      const url = config?.url ?? ''
      const isAuthEndpoint = url.includes('/api/auth/login') || url.includes('/api/auth/refresh') || url.includes('/api/auth/logout')
      if (isAuthEndpoint || config?._aetherisSessionProbe) return Promise.reject(error)
      if (config && !config._aetherisRetried && !isAuthEndpoint) {
        config._aetherisRetried = true
        try {
          await refreshSession(config._aetherisRevision ?? '')
          return http(config)
        } catch {
          // A rejected rotation must not immediately log out a different tab's
          // newly valid session. The server leaves its cookies intact on 401.
          await waitForConcurrentRefresh(config._aetherisRevision ?? '')
          try {
            const probe = await http.get<SessionUser>('/api/auth/me', {
              _aetherisSessionProbe: true
            } as Parameters<typeof http.get>[1] & RetryableConfig)
            setAuthenticated(probe.data)
            return http(config)
          } catch (probeError) {
            if ((probeError as { response?: { status?: number } }).response?.status !== 401) {
              return Promise.reject(probeError)
            }
            // Both renewal and an independent signed identity check rejected authentication.
          }
        }
      }
      if (!window.location.pathname.startsWith('/login')) {
        clearAuthenticated()
        window.location.href = '/login'
      }
    }
    return Promise.reject(error)
  }
)

export default http
