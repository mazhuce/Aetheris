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

export interface AuditLog {
  id: number
  username: string
  userRole: string
  method: string
  path: string
  statusCode: number
  durationMs: number
  clientIp?: string
  createdAt?: string
}

export interface AuditLogQuery {
  username?: string
  method?: string
  success?: boolean
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

export function listAuditLogs(params: AuditLogQuery = {}): Promise<AuditLog[]> {
  return http.get<AuditLog[]>('/api/system/audit-logs', { params }).then((res) => res.data)
}
