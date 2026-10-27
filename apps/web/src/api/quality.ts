import http from './http'
import { approvalMutationConfig, readExecutionApproval, type ExecutionApproval } from './executionApproval'
import { collectVisiblePages, type VisibleListPage, type VisibleListOptions } from './metadata'

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
  lastCheckStatus?: 'passed' | 'failed' | 'error' | 'unknown'
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
  status: 'running' | 'passed' | 'failed' | 'error' | 'unknown'
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
  return collectVisiblePages((cursor, signal) => listRulesPage({ cursor, size: 200, signal }))
}

export function listRulesPage(options: VisibleListOptions = {}, filters: { keyword?: string; ruleType?: string; status?: string } = {}): Promise<VisibleListPage<QualityRule>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<QualityRule>>('/api/quality/rules/page', { params: { ...filters, ...paging }, signal }).then((res) => res.data)
}

export function createRule(payload: QualityRuleInput): Promise<QualityRule> {
  return http.post<QualityRule>('/api/quality/rules', payload).then((res) => res.data)
}

export function updateRule(id: number, payload: Partial<QualityRuleInput>): Promise<QualityRule> {
  return http.put<QualityRule>(`/api/quality/rules/${id}`, payload).then((res) => res.data)
}

export function deleteRule(id: number): Promise<void> {
  return http.delete(`/api/quality/rules/${id}`).then(() => undefined)
}

export function runRule(id: number): Promise<QualityCheckResult> {
  return http.post<QualityCheckResult>(`/api/quality/rules/${id}/run`, undefined, { timeout: 70000 }).then((res) => res.data)
}

export function listRuleResults(id: number, limit = 30): Promise<QualityCheckResult[]> {
  return listRuleResultsPage(id, { size: Math.min(Math.max(limit, 1), 200) }).then((page) => page.items)
}

export function listRuleResultsPage(id: number, options: VisibleListOptions = {}): Promise<VisibleListPage<QualityCheckResult>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<QualityCheckResult>>(`/api/quality/rules/${id}/results/page`, { params: paging, signal }).then((res) => res.data)
}

export function listAllResults(status?: string, ruleId?: number, limit = 100): Promise<QualityCheckResult[]> {
  return listAllResultsPage(status, ruleId, { size: Math.min(Math.max(limit, 1), 200) }).then((page) => page.items)
}

export function listAllResultsPage(status?: string, ruleId?: number, options: VisibleListOptions = {}): Promise<VisibleListPage<QualityCheckResult>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<QualityCheckResult>>('/api/quality/rules/results/page', {
    params: { status: status || undefined, ruleId: ruleId || undefined, ...paging }, signal
  }).then((res) => res.data)
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
  return listQualityQuarantinePage(status, { size: Math.min(Math.max(limit, 1), 200) }).then((page) => page.items)
}

export function listQualityQuarantinePage(status = 'open', options: VisibleListOptions = {}): Promise<VisibleListPage<QualityQuarantineRecord>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<QualityQuarantineRecord>>('/api/quality/quarantine/page', {
    params: { status, ...paging }, signal
  }).then((res) => res.data)
}

export function releaseQualityQuarantine(id: number): Promise<QualityQuarantineRecord> {
  return http.post<QualityQuarantineRecord>(`/api/quality/quarantine/${id}/release`).then((res) => res.data)
}

/** A fresh view-authorized definition, never a caller supplied approval snapshot. */
export function getRule(id: number): Promise<QualityRule> {
  return http.get<QualityRule>(`/api/quality/rules/${id}`).then((res) => res.data)
}

export function getRuleApproval(id: number): Promise<ExecutionApproval> {
  return http.get<unknown>(`/api/quality/rules/${id}/approval`).then((res) => readExecutionApproval(res.data))
}

export function approveRule(id: number, expectedRevision: number): Promise<ExecutionApproval> {
  return http.post<unknown>(`/api/quality/rules/${id}/approve`, { expectedRevision }, approvalMutationConfig).then((res) => readExecutionApproval(res.data))
}

export function bindRuleCron(id: number, expectedRevision: number, expectedGeneration: number): Promise<ExecutionApproval> {
  return http.post<unknown>(`/api/quality/rules/${id}/cron-binding`, { expectedRevision, expectedGeneration }, approvalMutationConfig).then((res) => readExecutionApproval(res.data))
}
