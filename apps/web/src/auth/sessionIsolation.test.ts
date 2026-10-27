import { QueryClient } from '@tanstack/react-query'
import { waitFor } from '@testing-library/react'
import type { AxiosResponse, InternalAxiosRequestConfig } from 'axios'
import http from '@/api/http'
import { login, logout } from '@/api/auth'
import { bindSessionCache } from './queryClient'
import { bootstrapSession, markLoggedIn, markLoggedOut } from './session'
import { getAuthSnapshot, resetAuthState, SESSION_CONTEXT_KEY, setAuthenticated } from './authState'
import { startSessionSynchronization } from './sessionSynchronization'

const alice = { id: 1, username: 'alice', role: 'viewer' as const, accessVersion: 2, permissionVersion: 3 }
const bob = { ...alice, id: 2, username: 'bob' }
const originalAdapter = http.defaults.adapter

it('an unconfirmed logout stays locally signed out after reload without reusing surviving cookies', async () => {
  setAuthenticated(alice)
  const adapter = vi.fn(async (config: InternalAxiosRequestConfig) => {
    if (config.url === '/api/auth/logout') throw Object.assign(new Error('Unavailable'), {
      config, response: { status: 503, data: { code: 'BROWSER_SESSION_LOGOUT_UNCONFIRMED' }, config, headers: {} }
    })
    return ok(config, alice)
  })
  http.defaults.adapter = adapter
  await expect(logout()).rejects.toBeDefined()
  resetAuthState()
  await expect(bootstrapSession()).resolves.toBe(false)
  expect(getAuthSnapshot().status).toBe('anonymous')
  expect(adapter).toHaveBeenCalledTimes(1)
})

it('logout only accepts the explicit no-content revocation contract', async () => {
  setAuthenticated(alice)
  http.defaults.adapter = async (config) => ok(config, { message: 'legacy success' })
  await expect(logout()).rejects.toBeDefined()
  expect(getAuthSnapshot().status).toBe('anonymous')
})
let stops: (() => void)[] = []
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>((done) => { resolve = done })
  return { promise, resolve }
}
function ok(config: InternalAxiosRequestConfig, data: unknown): AxiosResponse {
  return { config, data, status: 200, statusText: 'OK', headers: {} }
}
afterEach(() => {
  stops.forEach((stop) => stop()); stops = []
  http.defaults.adapter = originalAdapter
})

it('logout aborts active cached queries and late completion cannot repopulate the cache', async () => {
  setAuthenticated(alice)
  const client = new QueryClient()
  stops.push(bindSessionCache(client), () => client.clear())
  client.setQueryData(['private'], { secret: 'A' })
  const late = deferred<string>()
  let signal!: AbortSignal
  const result = client.fetchQuery({ queryKey: ['pending'], queryFn: (ctx) => { signal = ctx.signal; return late.promise } })
  const rejected = expect(result).rejects.toBeDefined()
  await waitFor(() => expect(signal).toBeDefined())
  markLoggedOut()
  expect(signal.aborted).toBe(true)
  expect(client.getQueryCache().getAll()).toHaveLength(0)
  late.resolve('A-late')
  await rejected
  expect(client.getQueryCache().getAll()).toHaveLength(0)
})

it('late bootstrap cannot resurrect a logged out identity', async () => {
  resetAuthState()
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  http.defaults.adapter = (config) => { sent = config; return late.promise }
  const result = bootstrapSession()
  await waitFor(() => expect(sent).toBeDefined())
  markLoggedOut()
  late.resolve(ok(sent, alice))
  await result
  expect(getAuthSnapshot().status).toBe('anonymous')
})

it('a new bootstrap does not share the old generation promise', async () => {
  resetAuthState()
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  let requests = 0
  http.defaults.adapter = (config) => {
    if (++requests === 1) { sent = config; return late.promise }
    return Promise.resolve(ok(config, bob))
  }
  const oldBootstrap = bootstrapSession()
  await waitFor(() => expect(sent).toBeDefined())
  resetAuthState()
  await bootstrapSession()
  late.resolve(ok(sent, alice))
  await oldBootstrap
  expect(requests).toBe(2)
  expect(getAuthSnapshot().user?.id).toBe(bob.id)
})

it('a changed shared context rejects an old response even before the storage event arrives', async () => {
  setAuthenticated(alice)
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  http.defaults.adapter = (config) => { sent = config; return late.promise }
  const result = http.get('/api/private')
  const rejected = expect(result).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
  await waitFor(() => expect(sent).toBeDefined())
  localStorage.setItem(SESSION_CONTEXT_KEY, JSON.stringify({ kind: 'changed', nonce: 'another-tab' }))
  late.resolve(ok(sent, { secret: 'old' }))
  await rejected
})

it('a delayed old logout storage event cannot erase a newer successful login', () => {
  stops.push(startSessionSynchronization())
  markLoggedIn(bob)
  window.dispatchEvent(new StorageEvent('storage', {
    key: SESSION_CONTEXT_KEY, newValue: JSON.stringify({ kind: 'logout', nonce: 'old' })
  }))
  expect(getAuthSnapshot().user?.id).toBe(bob.id)
})

it('the current logout storage event clears local state and cache', () => {
  setAuthenticated(alice)
  const client = new QueryClient()
  stops.push(startSessionSynchronization(), bindSessionCache(client), () => client.clear())
  client.setQueryData(['secret'], 'A')
  const value = JSON.stringify({ kind: 'logout', nonce: 'current' })
  localStorage.setItem(SESSION_CONTEXT_KEY, value)
  window.dispatchEvent(new StorageEvent('storage', { key: SESSION_CONTEXT_KEY, newValue: value }))
  expect(getAuthSnapshot().status).toBe('anonymous')
  expect(client.getQueryData(['secret'])).toBeUndefined()
})

it('focus revalidation observes permission changes and coalesces concurrent probes', async () => {
  setAuthenticated(alice)
  const client = new QueryClient()
  client.setQueryData(['secret'], 'A')
  stops.push(startSessionSynchronization(), bindSessionCache(client), () => client.clear())
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  const adapter = vi.fn((config: InternalAxiosRequestConfig) => { sent = config; return late.promise })
  http.defaults.adapter = adapter
  window.dispatchEvent(new Event('focus'))
  window.dispatchEvent(new Event('focus'))
  await waitFor(() => expect(sent).toBeDefined())
  late.resolve(ok(sent, { ...alice, permissionVersion: 4 }))
  await waitFor(() => expect(getAuthSnapshot().user?.permissionVersion).toBe(4))
  expect(adapter).toHaveBeenCalledTimes(1)
  expect(client.getQueryData(['secret'])).toBeUndefined()
})

it('a delayed logout response never clears a newer in-memory identity', async () => {
  setAuthenticated(alice)
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  http.defaults.adapter = (config) => { sent = config; return late.promise }
  const result = logout()
  const rejected = expect(result).rejects.toMatchObject({ code: 'SESSION_CHANGED' })
  await waitFor(() => expect(sent).toBeDefined())
  markLoggedIn(bob)
  late.resolve(ok(sent, null))
  await rejected
  expect(getAuthSnapshot().user?.id).toBe(bob.id)
})

it('login cannot start without cross-tab locks', async () => {
  Reflect.deleteProperty(navigator, 'locks')
  const adapter = vi.fn()
  http.defaults.adapter = adapter
  await expect(login('alice', 'fixture-only')).rejects.toMatchObject({ code: 'SESSION_UNAVAILABLE' })
  expect(adapter).not.toHaveBeenCalled()
  expect(getAuthSnapshot().status).toBe('anonymous')
})

it('storage quota failure cannot silently preserve the old authenticated cache', () => {
  setAuthenticated(alice)
  const client = new QueryClient()
  stops.push(bindSessionCache(client), () => client.clear())
  client.setQueryData(['secret'], 'A')
  vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new DOMException('quota', 'QuotaExceededError') })
  expect(() => markLoggedIn(bob)).toThrow('会话存储不可用')
  expect(getAuthSnapshot().status).toBe('anonymous')
  expect(client.getQueryData(['secret'])).toBeUndefined()
})

it('storage read failure rejects a late successful response', async () => {
  setAuthenticated(alice)
  const client = new QueryClient()
  stops.push(bindSessionCache(client), () => client.clear())
  client.setQueryData(['secret'], 'A')
  const late = deferred<AxiosResponse>()
  let sent!: InternalAxiosRequestConfig
  http.defaults.adapter = (config) => { sent = config; return late.promise }
  const result = http.get('/api/private')
  const rejected = expect(result).rejects.toMatchObject({ code: 'SESSION_UNAVAILABLE' })
  await waitFor(() => expect(sent).toBeDefined())
  vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new DOMException('denied', 'SecurityError') })
  late.resolve(ok(sent, { secret: 'A' }))
  await rejected
  expect(getAuthSnapshot().status).toBe('anonymous')
  expect(client.getQueryData(['secret'])).toBeUndefined()
})
