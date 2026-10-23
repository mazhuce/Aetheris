import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import http from './http'
import { setAuthenticated, getAuthSnapshot } from '@/auth/authState'

const originalAdapter = http.defaults.adapter
const originalLocation = window.location

/** 用桩对象替换 window.location，避免 jsdom 真实导航 */
function stubLocation(pathname: string) {
  Object.defineProperty(window, 'location', {
    value: { pathname, href: '' },
    writable: true,
    configurable: true
  })
}

function okAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  return Promise.resolve({ data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config })
}

function unauthorizedAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
  const error = new Error('Unauthorized') as Error & { response?: Partial<AxiosResponse> }
  ;(error as Error & { config?: InternalAxiosRequestConfig }).config = config
  error.response = { status: 401, statusText: 'Unauthorized', headers: {}, config, data: null }
  return Promise.reject(error)
}

afterEach(() => {
  http.defaults.adapter = originalAdapter
  Object.defineProperty(window, 'location', { value: originalLocation, configurable: true })
  localStorage.removeItem('aetheris.session.revision')
  vi.unstubAllGlobals()
})

describe('http 请求拦截器', () => {
  it('使用 HttpOnly cookie 凭据且不在请求头暴露 token', async () => {
    const adapter = vi.fn((config: InternalAxiosRequestConfig) => okAdapter(config))
    http.defaults.adapter = adapter

    await http.get('/ping')

    expect(adapter).toHaveBeenCalledTimes(1)
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBeUndefined()
    expect(adapter.mock.calls[0][0].withCredentials).toBe(true)
  })

  it('401 时用 HttpOnly refresh cookie 续期并只重试原请求一次', async () => {
    let requestCount = 0
    const adapter = vi.fn((config: InternalAxiosRequestConfig) => {
      if (config.url === '/api/auth/refresh') {
        return okAdapter({ ...config, data: { username: 'alice', role: 'viewer' } })
      }
      requestCount += 1
      if (requestCount === 1) return unauthorizedAdapter(config)
      return okAdapter(config)
    })
    http.defaults.adapter = adapter

    const response = await http.get('/protected')

    expect(response.status).toBe(200)
    expect(adapter).toHaveBeenCalledTimes(3)
    expect(adapter.mock.calls[1][0].url).toBe('/api/auth/refresh')
    expect(adapter.mock.calls[2][0].url).toBe('/protected')
    expect(adapter.mock.calls[2][0].headers.get('Authorization')).toBeUndefined()
  })
})

describe('http 401 处理', () => {
  it('刷新竞争失败保留会话，并用另一个标签页更新的 Cookie 验证后重试', async () => {
    stubLocation('/workbench')
    setAuthenticated({ username: 'alice', role: 'viewer' })
    let originalCalls = 0
    http.defaults.adapter = (config) => {
      if (config.url === '/api/auth/refresh') return unauthorizedAdapter(config)
      if (config.url === '/api/auth/me') return Promise.resolve({
        data: { username: 'alice', role: 'viewer' }, status: 200, statusText: 'OK', headers: {}, config
      })
      return ++originalCalls === 1 ? unauthorizedAdapter(config) : okAdapter(config)
    }
    await expect(http.get('/protected')).resolves.toMatchObject({ status: 200 })
    expect(getAuthSnapshot().status).toBe('authenticated')
    expect(window.location.href).toBe('')
  })

  it('单独的 refresh 401 不主动清理另一个标签页的认证状态', async () => {
    stubLocation('/workbench')
    setAuthenticated({ username: 'alice', role: 'viewer' })
    http.defaults.adapter = unauthorizedAdapter
    await expect(http.post('/api/auth/refresh')).rejects.toThrow('Unauthorized')
    expect(getAuthSnapshot().status).toBe('authenticated')
    expect(window.location.href).toBe('')
  })

  it('两个独立标签页使用 Web Locks 只轮换一次共享 Cookie', async () => {
    let tail: Promise<unknown> = Promise.resolve()
    const request = vi.fn((_name: string, callback: () => Promise<void>) => {
      const next = tail.then(callback)
      tail = next.catch(() => undefined)
      return next
    })
    Object.defineProperty(navigator, 'locks', { value: { request }, configurable: true })
    vi.resetModules()
    const first = (await import('./http')).default
    vi.resetModules()
    const second = (await import('./http')).default
    let cookiesValid = false
    let rotations = 0
    const adapter = async (config: InternalAxiosRequestConfig) => {
      if (config.url === '/api/auth/refresh') {
        rotations++
        await new Promise((resolve) => window.setTimeout(resolve, 10))
        cookiesValid = true
        return { data: { username: 'alice', role: 'viewer' }, status: 200, statusText: 'OK', headers: {}, config }
      }
      return cookiesValid ? okAdapter(config) : unauthorizedAdapter(config)
    }
    first.defaults.adapter = adapter
    second.defaults.adapter = adapter
    await Promise.all([first.get('/protected'), second.get('/protected')])
    expect(rotations).toBe(1)
    expect(request).toHaveBeenCalledTimes(2)
    Reflect.deleteProperty(navigator, 'locks')
  })
  it('非登录页收到 401 时清除凭据并跳转 /login', async () => {
    stubLocation('/system/users')
    setAuthenticated({ username: 'alice', role: 'admin' })
    http.defaults.adapter = unauthorizedAdapter

    await expect(http.get('/ping')).rejects.toThrow('Unauthorized')
    expect(getAuthSnapshot().status).toBe('anonymous')
    expect(window.location.href).toBe('/login')
  })

  it('登录页收到 401 时不清理凭据也不跳转', async () => {
    stubLocation('/login')
    setAuthenticated({ username: 'alice', role: 'admin' })
    http.defaults.adapter = unauthorizedAdapter

    await expect(http.get('/ping')).rejects.toThrow('Unauthorized')
    expect(getAuthSnapshot().status).toBe('authenticated')
    expect(window.location.href).toBe('')
  })

  it('非 401 错误不触发跳转', async () => {
    stubLocation('/system/users')
    http.defaults.adapter = (config) => {
      const error = new Error('Server Error') as Error & { response?: Partial<AxiosResponse> }
      error.response = { status: 500, statusText: 'Server Error', headers: {}, config, data: null }
      return Promise.reject(error)
    }

    await expect(http.get('/ping')).rejects.toThrow('Server Error')
    expect(window.location.href).toBe('')
  })
})
