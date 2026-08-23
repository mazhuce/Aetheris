import http from './http'

export interface QualityRule {
  id: number
  name: string
  ruleType: string
  targetTable: string
  expression?: string
  threshold?: number
  severity?: string
  status?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface QualityRuleInput {
  name: string
  ruleType: string
  targetTable: string
  expression?: string
  threshold?: number
  severity?: string
  status?: string
  description?: string
}

export function listRules(): Promise<QualityRule[]> {
  return http.get<QualityRule[]>('/api/quality/rules').then((res) => res.data)
}

export function createRule(payload: QualityRuleInput): Promise<QualityRule> {
  return http.post<QualityRule>('/api/quality/rules', payload).then((res) => res.data)
}

export function updateRule(id: number, payload: QualityRuleInput): Promise<QualityRule> {
  return http.put<QualityRule>(`/api/quality/rules/${id}`, payload).then((res) => res.data)
}

export function deleteRule(id: number): Promise<void> {
  return http.delete(`/api/quality/rules/${id}`).then(() => undefined)
}
