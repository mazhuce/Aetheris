import type { InternalAxiosRequestConfig, AxiosResponse } from 'axios'
import http, { TOKEN_KEY, USERNAME_KEY, ROLE_KEY } from './http'

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

afterEach(() => {
  http.defaults.adapter = originalAdapter
  Object.defineProperty(window, 'location', { value: originalLocation, configurable: true })
})

describe('http 请求拦截器', () => {
  it('存在 token 时附加 Authorization: Bearer', async () => {
    localStorage.setItem(TOKEN_KEY, 't-123')
    const adapter = vi.fn((config: InternalAxiosRequestConfig) => okAdapter(config))
    http.defaults.adapter = adapter

    await http.get('/ping')

    expect(adapter).toHaveBeenCalledTimes(1)
    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBe('Bearer t-123')
  })

  it('无 token 时不附加 Authorization', async () => {
    const adapter = vi.fn((config: InternalAxiosRequestConfig) => okAdapter(config))
    http.defaults.adapter = adapter

    await http.get('/ping')

    expect(adapter.mock.calls[0][0].headers.get('Authorization')).toBeUndefined()
  })
})

describe('http 401 处理', () => {
  function unauthorizedAdapter(config: InternalAxiosRequestConfig): Promise<AxiosResponse> {
    const error = new Error('Unauthorized') as Error & { response?: Partial<AxiosResponse> }
    error.response = { status: 401, statusText: 'Unauthorized', headers: {}, config, data: null }
    return Promise.reject(error)
  }

  it('非登录页收到 401 时清除凭据并跳转 /login', async () => {
    stubLocation('/system/users')
    localStorage.setItem(TOKEN_KEY, 't-123')
    localStorage.setItem(USERNAME_KEY, 'alice')
    localStorage.setItem(ROLE_KEY, 'admin')
    http.defaults.adapter = unauthorizedAdapter

    await expect(http.get('/ping')).rejects.toThrow('Unauthorized')
    expect(localStorage.getItem(TOKEN_KEY)).toBeNull()
    expect(localStorage.getItem(USERNAME_KEY)).toBeNull()
    expect(localStorage.getItem(ROLE_KEY)).toBeNull()
    expect(window.location.href).toBe('/login')
  })

  it('登录页收到 401 时不清理凭据也不跳转', async () => {
    stubLocation('/login')
    localStorage.setItem(TOKEN_KEY, 't-123')
    localStorage.setItem(ROLE_KEY, 'admin')
    http.defaults.adapter = unauthorizedAdapter

    await expect(http.get('/ping')).rejects.toThrow('Unauthorized')
    expect(localStorage.getItem(TOKEN_KEY)).toBe('t-123')
    expect(localStorage.getItem(ROLE_KEY)).toBe('admin')
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
