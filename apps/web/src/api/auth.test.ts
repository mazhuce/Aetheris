import type { Mock } from 'vitest'
import http from './http'
import {
  login,
  listAuthUsers,
  createAuthUser,
  updateAuthUser,
  deleteAuthUser,
  getMyProfile,
  updateMyProfile,
  changeMyPassword
} from './auth'
import type { AxiosResponse } from 'axios'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

const mockedHttp = http as unknown as Record<'get' | 'post' | 'put' | 'delete', Mock>

function resolved<T>(data: T) {
  return Promise.resolve({ data } as AxiosResponse<T>)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('auth api', () => {
  it('login 向 /api/auth/login 发送用户名密码并返回 data', async () => {
    const result = { username: 'alice', role: 'admin' as const, expiresIn: 3600 }
    mockedHttp.post.mockReturnValueOnce(resolved(result))

    await expect(login('alice', 'secret')).resolves.toEqual(result)
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/login', { username: 'alice', password: 'secret' })
  })

  it('listAuthUsers 请求用户列表', async () => {
    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await expect(listAuthUsers()).resolves.toEqual([])
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/users')
  })

  it('createAuthUser 发送完整 payload', async () => {
    const payload = {
      username: 'bob',
      password: 'pwd',
      role: 'viewer' as const,
      status: 'active' as const
    }
    mockedHttp.post.mockReturnValueOnce(resolved({ id: 1, ...payload }))
    await createAuthUser(payload)
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/users', payload)
  })

  it('updateAuthUser 使用 RESTful 路径', async () => {
    mockedHttp.put.mockReturnValueOnce(resolved({ id: 7 } as never))
    await updateAuthUser(7, { role: 'admin', status: 'disabled' })
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/users/7', { role: 'admin', status: 'disabled' })
  })

  it('deleteAuthUser 返回 undefined', async () => {
    mockedHttp.delete.mockReturnValueOnce(resolved(null))
    await expect(deleteAuthUser(3)).resolves.toBeUndefined()
    expect(mockedHttp.delete).toHaveBeenCalledWith('/api/auth/users/3')
  })

  it('getMyProfile 请求 /api/auth/me', async () => {
    mockedHttp.get.mockReturnValueOnce(resolved({ id: 1, username: 'alice' } as never))
    await getMyProfile()
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/me')
  })

  it('updateMyProfile 发送资料字段', async () => {
    const payload = { nickname: 'Alice', email: 'a@b.c' }
    mockedHttp.put.mockReturnValueOnce(resolved({ id: 1 } as never))
    await updateMyProfile(payload)
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/me', payload)
  })

  it('changeMyPassword 发送新旧密码', async () => {
    mockedHttp.put.mockReturnValueOnce(resolved({ message: 'ok', reloginRequired: true }))
    await expect(changeMyPassword('old', 'new')).resolves.toEqual({ message: 'ok', reloginRequired: true })
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/me/password', { currentPassword: 'old', newPassword: 'new' })
  })
})
