import http from './http'
import { markLoggedIn, markLoggedOut } from '@/auth/session'
import { captureSession, isCurrentSession, logoutIntent, type SessionUser } from '@/auth/authState'
import { coordinateSessionMutation } from '@/auth/refreshCoordinator'

export interface LoginResult extends SessionUser {
  expiresIn: number
}

export type UserRole = 'admin' | 'viewer'

export interface AuthUser {
  id: number
  username: string
  nickname?: string
  email?: string
  phone?: string
  role: UserRole
  status: 'active' | 'disabled'
  createdAt?: string
  updatedAt?: string
  accessVersion?: number
  permissionVersion?: number
}

export interface CreateAuthUserInput {
  username: string
  password: string
  nickname?: string
  email?: string
  phone?: string
  role: UserRole
  status: 'active' | 'disabled'
}

export interface UpdateAuthUserInput {
  password?: string
  nickname?: string
  email?: string
  phone?: string
  role: UserRole
  status: 'active' | 'disabled'
}

export function login(username: string, password: string): Promise<LoginResult> {
  markLoggedOut(logoutIntent()?.revocation)
  const started = captureSession()
  return coordinateSessionMutation(async () => {
    if (!isCurrentSession(started)) throw new Error('会话已变化，请重新登录')
    const response = await http.post<LoginResult>('/api/auth/login', { username, password })
    if (!isCurrentSession(started)) throw new Error('会话已变化，请重新登录')
    markLoggedIn(response.data)
    return response.data
  })
}

export function logout(): Promise<void> {
  markLoggedOut('pending')
  const started = captureSession()
  return coordinateSessionMutation(async () => {
    if (!isCurrentSession(started)) throw Object.assign(new Error('会话已变化，请重新操作'), { code: 'SESSION_CHANGED' })
    try {
      const response = await http.post('/api/auth/logout')
      if (response.status !== 204) throw new Error('服务端会话撤销未确认')
      if (isCurrentSession(started)) markLoggedOut('confirmed')
    } catch (error) {
      if (isCurrentSession(started)) markLoggedOut('unconfirmed')
      throw error
    }
  })
}

export function listAuthUsers(): Promise<AuthUser[]> {
  return http.get<AuthUser[]>('/api/auth/users').then((res) => res.data)
}

export function createAuthUser(payload: CreateAuthUserInput): Promise<AuthUser> {
  return http.post<AuthUser>('/api/auth/users', payload).then((res) => res.data)
}

export function updateAuthUser(id: number, payload: UpdateAuthUserInput): Promise<AuthUser> {
  return http.put<AuthUser>(`/api/auth/users/${id}`, payload).then((res) => res.data)
}

export function deleteAuthUser(id: number): Promise<void> {
  return http.delete(`/api/auth/users/${id}`).then(() => undefined)
}

export interface UpdateProfileInput {
  nickname?: string
  email?: string
  phone?: string
}

export interface ChangePasswordResult {
  message: string
  reloginRequired: boolean
}

/** 当前登录用户资料（身份由网关签名并经服务端验证，不接受指定用户）。 */
export function getMyProfile(): Promise<AuthUser> {
  return http.get<AuthUser>('/api/auth/me').then((res) => res.data)
}

export function updateMyProfile(payload: UpdateProfileInput): Promise<AuthUser> {
  return http.put<AuthUser>('/api/auth/me', payload).then((res) => res.data)
}

export function changeMyPassword(currentPassword: string, newPassword: string): Promise<ChangePasswordResult> {
  return http.put<ChangePasswordResult>('/api/auth/me/password', { currentPassword, newPassword }).then((res) => {
    if (res.data.reloginRequired) markLoggedOut()
    return res.data
  })
}
