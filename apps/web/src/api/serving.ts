import http from './http'

// —— 数据订阅：订阅需求与下发配置 ——

export interface DataSubscription {
  id: number
  subscriptionName: string
  subscriber: string
  targetType?: 'table' | 'api' | 'product' | 'dataset'
  targetName: string
  deliverType?: 'api' | 'file' | 'stream' | 'email'
  frequency?: string
  status?: 'active' | 'paused' | 'cancelled'
  configJson?: unknown
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface SubscriptionInput {
  subscriptionName: string
  subscriber: string
  targetType?: string
  targetName: string
  deliverType?: string
  frequency?: string
  status?: string
}

export function listSubscriptions(status?: string): Promise<DataSubscription[]> {
  return http
    .get<DataSubscription[]>('/api/serving/subscriptions', { params: status ? { status } : {} })
    .then((res) => res.data)
}

export function createSubscription(payload: SubscriptionInput): Promise<DataSubscription> {
  return http.post<DataSubscription>('/api/serving/subscriptions', payload).then((res) => res.data)
}

export function updateSubscription(id: number, payload: SubscriptionInput): Promise<DataSubscription> {
  return http.put<DataSubscription>(`/api/serving/subscriptions/${id}`, payload).then((res) => res.data)
}

export function deleteSubscription(id: number): Promise<void> {
  return http.delete(`/api/serving/subscriptions/${id}`).then(() => undefined)
}

// —— 服务授权：数据服务的使用授权与访问范围 ——

export interface ServiceGrant {
  id: number
  apiPath: string
  grantee: string
  grantType?: 'user' | 'role'
  scope?: 'read' | 'write'
  expiresAt?: string | null
  status?: 'active' | 'revoked'
  grantedBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface GrantInput {
  apiPath: string
  grantee: string
  grantType?: string
  scope?: string
  expiresAt?: string | null
}

export function listGrants(status?: string): Promise<ServiceGrant[]> {
  return http
    .get<ServiceGrant[]>('/api/serving/grants', { params: status ? { status } : {} })
    .then((res) => res.data)
}

export function createGrant(payload: GrantInput): Promise<ServiceGrant> {
  return http.post<ServiceGrant>('/api/serving/grants', payload).then((res) => res.data)
}

export function updateGrant(id: number, payload: GrantInput): Promise<ServiceGrant> {
  return http.put<ServiceGrant>(`/api/serving/grants/${id}`, payload).then((res) => res.data)
}

export function deleteGrant(id: number): Promise<void> {
  return http.delete(`/api/serving/grants/${id}`).then(() => undefined)
}

export function revokeGrant(id: number): Promise<ServiceGrant> {
  return http.post<ServiceGrant>(`/api/serving/grants/${id}/revoke`, undefined).then((res) => res.data)
}
