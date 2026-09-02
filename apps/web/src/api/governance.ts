import http from './http'

export interface GovernancePolicy {
  id: number
  name: string
  policyType: string
  targetTable: string
  ruleExpression?: string
  status?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface GovernancePolicyInput {
  name: string
  policyType: string
  targetTable: string
  ruleExpression?: string
  status?: string
  description?: string
}

export function listPolicies(): Promise<GovernancePolicy[]> {
  return http.get<GovernancePolicy[]>('/api/governance/policies').then((res) => res.data)
}

export function createPolicy(payload: GovernancePolicyInput): Promise<GovernancePolicy> {
  return http.post<GovernancePolicy>('/api/governance/policies', payload).then((res) => res.data)
}

export function updatePolicy(id: number, payload: GovernancePolicyInput): Promise<GovernancePolicy> {
  return http.put<GovernancePolicy>(`/api/governance/policies/${id}`, payload).then((res) => res.data)
}

export function deletePolicy(id: number): Promise<void> {
  return http.delete(`/api/governance/policies/${id}`).then(() => undefined)
}

// —— 数据标准：字段命名、编码与取值规范 ——

export interface DataStandard {
  id: number
  standardCode: string
  standardName: string
  standardType?: 'naming' | 'encoding' | 'value' | 'format'
  applyScope?: string
  definition?: string
  ruleExpression?: string
  referenceDoc?: string
  status?: 'draft' | 'published' | 'deprecated'
  version?: number
  owner?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface DataStandardInput {
  standardCode: string
  standardName: string
  standardType?: string
  applyScope?: string
  definition?: string
  ruleExpression?: string
  referenceDoc?: string
  status?: string
  version?: number
  owner?: string
}

export function listDataStandards(keyword?: string, status?: string, standardType?: string): Promise<DataStandard[]> {
  return http
    .get<DataStandard[]>('/api/governance/standards', {
      params: { keyword: keyword || undefined, status: status || undefined, standardType: standardType || undefined }
    })
    .then((res) => res.data)
}

export function createDataStandard(payload: DataStandardInput): Promise<DataStandard> {
  return http.post<DataStandard>('/api/governance/standards', payload).then((res) => res.data)
}

export function updateDataStandard(id: number, payload: DataStandardInput): Promise<DataStandard> {
  return http.put<DataStandard>(`/api/governance/standards/${id}`, payload).then((res) => res.data)
}

export function deleteDataStandard(id: number): Promise<void> {
  return http.delete(`/api/governance/standards/${id}`).then(() => undefined)
}
