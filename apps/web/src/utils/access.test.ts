import { getCurrentRole, canManagePlatform } from './access'
import { ROLE_KEY } from '@/api/http'

describe('access', () => {
  it('本地存储为 admin 时返回 admin 并可管理平台', () => {
    localStorage.setItem(ROLE_KEY, 'admin')
    expect(getCurrentRole()).toBe('admin')
    expect(canManagePlatform()).toBe(true)
  })

  it('本地存储为 viewer 时返回 viewer 且不可管理平台', () => {
    localStorage.setItem(ROLE_KEY, 'viewer')
    expect(getCurrentRole()).toBe('viewer')
    expect(canManagePlatform()).toBe(false)
  })

  it('未登录（无存储值）时默认 viewer', () => {
    expect(getCurrentRole()).toBe('viewer')
    expect(canManagePlatform()).toBe(false)
  })
})
