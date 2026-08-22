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

export function pingSystem(): Promise<PingResult> {
  return http.get<PingResult>('/api/system/ping').then((res) => res.data)
}

export function listConfigs(): Promise<SystemConfig[]> {
  return http.get<SystemConfig[]>('/api/system/configs').then((res) => res.data)
}
