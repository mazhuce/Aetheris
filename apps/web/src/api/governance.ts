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
