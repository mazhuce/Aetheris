import { getCurrentRole, canManagePlatform } from './access'
import { setAuthenticated } from '@/auth/authState'

describe('access', () => {
  it('内存会话为 admin 时返回 admin 并可管理平台', () => {
    setAuthenticated({ username: 'alice', role: 'admin' })
    expect(getCurrentRole()).toBe('admin')
    expect(canManagePlatform()).toBe(true)
  })

  it('内存会话为 viewer 时返回 viewer 且不可管理平台', () => {
    setAuthenticated({ username: 'alice', role: 'viewer' })
    expect(getCurrentRole()).toBe('viewer')
    expect(canManagePlatform()).toBe(false)
  })

  it('未登录（无存储值）时默认 viewer', () => {
    expect(getCurrentRole()).toBe('viewer')
    expect(canManagePlatform()).toBe(false)
  })
})
