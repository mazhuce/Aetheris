import axios from 'axios'
import { clearAuthenticated, setAuthenticated } from '@/auth/authState'

type RetryableConfig = { _aetherisRetried?: boolean }

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '',
  timeout: 15000,
  withCredentials: true
})

let refreshPromise: Promise<void> | null = null

function refreshSession(): Promise<void> {
  if (!refreshPromise) {
    refreshPromise = http.post<{ username: string; nickname?: string; role: 'admin' | 'viewer' }>(
      '/api/auth/refresh'
    ).then((response) => {
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
      if (config && !config._aetherisRetried && !isAuthEndpoint) {
        config._aetherisRetried = true
        try {
          await refreshSession()
          return http(config)
        } catch {
          // Fall through to the single logout redirect below.
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
