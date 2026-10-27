import axios, { AxiosError, type AxiosRequestConfig } from 'axios'
import {
  captureSession, clearAuthenticated, isCurrentSession, parseSessionUser, sessionContextRevision, sessionIdentity,
  setAuthenticated, type SessionStamp, type SessionUser
} from '@/auth/authState'
import { coordinateRefresh, sessionRevision, waitForConcurrentRefresh } from '@/auth/refreshCoordinator'

type RetryableConfig = {
  _aetherisRetried?: boolean
  _aetherisSessionProbe?: boolean
  _aetherisRevision?: string
  _aetherisSession?: SessionStamp
}

const http = axios.create({
  baseURL: import.meta.env.VITE_API_BASE_URL || '', timeout: 15_000, withCredentials: true
})

function sessionChanged(): AxiosError {
  if (sessionContextRevision() === null) {
    clearAuthenticated()
    return new AxiosError('浏览器会话存储不可用，请允许本站存储后重新登录', 'SESSION_UNAVAILABLE')
  }
  return new AxiosError('会话或权限已变化，旧请求已取消，请重新操作', 'SESSION_CHANGED')
}
function isAuthEndpoint(url = ''): boolean {
  return ['/api/auth/login', '/api/auth/refresh', '/api/auth/logout'].includes(url.split('?')[0])
}
function stillCurrent(config: RetryableConfig): boolean {
  return !!config._aetherisSession && isCurrentSession(config._aetherisSession)
}

http.interceptors.request.use((config) => {
  const state = config as typeof config & RetryableConfig
  state._aetherisSession ??= captureSession()
  state._aetherisRevision ??= sessionRevision()
  if (!stillCurrent(state)) throw sessionChanged()
  return config
})

let refreshPromise: Promise<SessionUser> | null = null
async function refreshSession(observed: string): Promise<SessionUser> {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      let refreshed: SessionUser | undefined
      try {
        await coordinateRefresh(observed, async () => {
          const response = await http.post('/api/auth/refresh')
          refreshed = parseSessionUser(response.data)
        })
      } catch (error) {
        if (['SESSION_CHANGED', 'SESSION_UNAVAILABLE'].includes((error as AxiosError).code ?? '')) throw error
        // A losing CAS never clears the winner's cookies. Verify them before any retry.
        await waitForConcurrentRefresh(observed)
      }
      if (refreshed) return refreshed
      const probe = await http.get('/api/auth/me', { _aetherisSessionProbe: true } as AxiosRequestConfig)
      return parseSessionUser(probe.data)
    })().finally(() => { refreshPromise = null })
  }
  return refreshPromise
}

http.interceptors.response.use(
  (response) => {
    if (!stillCurrent(response.config as RetryableConfig)) throw sessionChanged()
    return response
  },
  async (error: AxiosError) => {
    const config = error.config as (AxiosRequestConfig & RetryableConfig) | undefined
    if (config && !stillCurrent(config)) throw sessionChanged()
    if (error.response?.status !== 401 || !config || isAuthEndpoint(config.url)
        || config._aetherisSessionProbe || config._aetherisRetried) throw error

    const original = config._aetherisSession!
    let user: SessionUser
    try {
      user = await refreshSession(config._aetherisRevision ?? '')
    } catch (refreshError) {
      if (!isCurrentSession(original)) throw sessionChanged()
      if ((refreshError as AxiosError).response?.status === 401) {
        clearAuthenticated()
        if (!window.location.pathname.startsWith('/login')) window.location.href = '/login'
      }
      throw refreshError
    }
    if (!isCurrentSession(original)) throw sessionChanged()
    setAuthenticated(user)
    if (original.identity !== null && original.identity !== sessionIdentity(user)) throw sessionChanged()

    // Even a nominally idempotent PUT/DELETE can carry stale user intent. Only safe
    // reads are replayed; mutation/query POST callers must explicitly submit again.
    if (!['get', 'head', 'options'].includes((config.method ?? 'get').toLowerCase())) {
      throw new AxiosError('会话已刷新，本次修改未自动重试，请核对状态后重新操作',
        'SESSION_RETRY_REQUIRED', error.config, error.request, error.response)
    }
    config._aetherisRetried = true
    config._aetherisSession = captureSession()
    config._aetherisRevision = sessionRevision()
    return http(config)
  }
)

export default http
