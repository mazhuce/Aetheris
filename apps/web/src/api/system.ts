import http from './http'

export interface PingResult {
  service: string
  version: string
  status: string
  timestamp: string
}

export interface SystemConfig {
  id: number
  configKey: string
  configValue: string
  description?: string
}

export interface SystemConfigInput {
  configKey: string
  configValue: string
  description?: string
}

export interface SysUser {
  id: number
  username: string
  nickname?: string
  email?: string
  phone?: string
  status?: string
  createdAt?: string
  updatedAt?: string
}

export interface SysUserInput {
  username: string
  nickname?: string
  email?: string
  phone?: string
  status?: string
}

export function pingSystem(): Promise<PingResult> {
  return http.get<PingResult>('/api/system/ping').then((res) => res.data)
}

export function listConfigs(): Promise<SystemConfig[]> {
  return http.get<SystemConfig[]>('/api/system/configs').then((res) => res.data)
}

export function createConfig(payload: SystemConfigInput): Promise<SystemConfig> {
  return http.post<SystemConfig>('/api/system/configs', payload).then((res) => res.data)
}

export function updateConfig(id: number, payload: SystemConfigInput): Promise<SystemConfig> {
  return http.put<SystemConfig>(`/api/system/configs/${id}`, payload).then((res) => res.data)
}

export function deleteConfig(id: number): Promise<void> {
  return http.delete(`/api/system/configs/${id}`).then(() => undefined)
}

export function listUsers(): Promise<SysUser[]> {
  return http.get<SysUser[]>('/api/system/users').then((res) => res.data)
}

export function createUser(payload: SysUserInput): Promise<SysUser> {
  return http.post<SysUser>('/api/system/users', payload).then((res) => res.data)
}

export function updateUser(id: number, payload: SysUserInput): Promise<SysUser> {
  return http.put<SysUser>(`/api/system/users/${id}`, payload).then((res) => res.data)
}

export function deleteUser(id: number): Promise<void> {
  return http.delete(`/api/system/users/${id}`).then(() => undefined)
}
