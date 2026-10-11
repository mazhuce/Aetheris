import http from './http'

export interface QualityRule {
  id: number
  name: string
  ruleType: string
  datasourceId?: number
  targetTable: string
  expression?: string
  threshold?: number
  severity?: string
  status?: string
  cronExpression?: string
  description?: string
  lastCheckStatus?: 'passed' | 'failed' | 'error'
  lastViolationCount?: number
  lastCheckedAt?: string
  lastError?: string
  createdAt?: string
  updatedAt?: string
}

export interface QualityRuleInput {
  name: string
  ruleType: string
  datasourceId: number
  targetTable: string
  expression?: string
  threshold?: number
  severity?: string
  status?: string
  cronExpression?: string
  description?: string
}

export interface QualityCheckResult {
  id: number
  ruleId: number
  status: 'running' | 'passed' | 'failed' | 'error'
  violationCount?: number
  thresholdValue?: number
  durationMs?: number
  message?: string
  triggeredBy?: string
  startedAt: string
  finishedAt?: string
  failureCode?: string
}

export interface QualityQuarantineRecord {
  id: number
  resultId?: number
  ruleId: number
  targetTable: string
  violationCount?: number
  thresholdValue?: number
  reason?: string
  status: 'open' | 'released' | 'discarded'
  createdAt: string
  releasedAt?: string
  releasedBy?: string
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

export function runRule(id: number): Promise<QualityCheckResult> {
  return http.post<QualityCheckResult>(`/api/quality/rules/${id}/run`, undefined, { timeout: 70000 }).then((res) => res.data)
}

export function listRuleResults(id: number, limit = 30): Promise<QualityCheckResult[]> {
  return http.get<QualityCheckResult[]>(`/api/quality/rules/${id}/results`, { params: { limit } }).then((res) => res.data)
}

export function listAllResults(status?: string, ruleId?: number, limit = 100): Promise<QualityCheckResult[]> {
  const params: Record<string, number | string> = { limit }
  if (status) params.status = status
  if (ruleId) params.ruleId = ruleId
  return http.get<QualityCheckResult[]>('/api/quality/rules/results/all', { params }).then((res) => res.data)
}

export interface QualitySummary {
  enabledRules: number
  passed: number
  failed: number
  errored: number
  checked: number
  /** 质量分（百分比，一位小数）；-1 表示尚无检查记录。 */
  score: number
}

export function getQualitySummary(): Promise<QualitySummary> {
  return http.get<QualitySummary>('/api/quality/rules/results/summary').then((res) => res.data)
}

export function listQualityQuarantine(status = 'open', limit = 100): Promise<QualityQuarantineRecord[]> {
  return http
    .get<QualityQuarantineRecord[]>('/api/quality/quarantine', { params: { status, limit } })
    .then((res) => res.data)
}

export function releaseQualityQuarantine(id: number): Promise<QualityQuarantineRecord> {
  return http.post<QualityQuarantineRecord>(`/api/quality/quarantine/${id}/release`).then((res) => res.data)
}
