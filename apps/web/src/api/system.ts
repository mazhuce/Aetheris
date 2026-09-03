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

export function listActivity(limit = 12): Promise<AuditLog[]> {
  return http.get<AuditLog[]>('/api/system/activity', { params: { limit } }).then((res) => res.data)
}

export interface PlatformNotification {
  id: number
  recipient: string
  source: string
  sourceId?: number
  level: 'info' | 'warning' | 'critical'
  title: string
  message?: string
  linkPath?: string
  isRead?: boolean
  createdAt?: string
}

export function listNotifications(unread?: boolean): Promise<PlatformNotification[]> {
  return http
    .get<PlatformNotification[]>('/api/system/notifications', { params: unread === undefined ? {} : { unread } })
    .then((res) => res.data)
}

export function getUnreadNotificationCount(): Promise<number> {
  return http.get<{ count: number }>('/api/system/notifications/unread-count').then((res) => res.data.count)
}

export function markNotificationRead(id: number): Promise<void> {
  return http.put(`/api/system/notifications/${id}/read`).then(() => undefined)
}

export function markAllNotificationsRead(): Promise<void> {
  return http.put('/api/system/notifications/read-all').then(() => undefined)
}

// —— 告警中心（运维视角）：admin 看全局，普通用户看自己 ——

export interface AlertStats {
  critical: number
  warning: number
  info: number
  total: number
}

export interface AlertQuery {
  level?: string
  source?: string
  unread?: boolean
}

export function listAlerts(query: AlertQuery = {}): Promise<PlatformNotification[]> {
  return http.get<PlatformNotification[]>('/api/system/alerts', { params: query }).then((res) => res.data)
}

export function getAlertStats(): Promise<AlertStats> {
  return http.get<AlertStats>('/api/system/alerts/stats').then((res) => res.data)
}

// —— 服务监控：聚合各微服务健康状态 ——

export interface ServiceStatus {
  name: string
  status: 'UP' | 'DOWN'
  detail?: string
  checkedAt?: string
}

export function getServiceHealth(): Promise<ServiceStatus[]> {
  return http.get<ServiceStatus[]>('/api/system/service-health').then((res) => res.data)
}
