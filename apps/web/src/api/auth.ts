import http from './http'

export interface LoginResult {
  token: string
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
