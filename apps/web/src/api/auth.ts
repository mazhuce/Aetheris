import http from './http'
import { markLoggedOut } from '@/auth/session'

export interface LoginResult {
  username: string
  nickname?: string
  role: UserRole
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
  return http.post<LoginResult>('/api/auth/login', { username, password }).then((res) => res.data)
}

export function logout(): Promise<void> {
  return http.post('/api/auth/logout').then(() => undefined).finally(markLoggedOut)
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

/** 当前登录用户资料（身份由网关注入的 X-User-Name 确定，不接受指定用户）。 */
export function getMyProfile(): Promise<AuthUser> {
  return http.get<AuthUser>('/api/auth/me').then((res) => res.data)
}

export function updateMyProfile(payload: UpdateProfileInput): Promise<AuthUser> {
  return http.put<AuthUser>('/api/auth/me', payload).then((res) => res.data)
}

export function changeMyPassword(currentPassword: string, newPassword: string): Promise<ChangePasswordResult> {
  return http.put<ChangePasswordResult>('/api/auth/me/password', { currentPassword, newPassword }).then((res) => res.data)
}
