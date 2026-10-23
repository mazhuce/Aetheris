import http from './http'

export interface Reconciliation {
  status: 'success' | 'failed' | 'passed' | 'error'
  confirmedRemoteStopped: boolean
  evidence: string
  rowsRead?: number
  rowsWritten?: number
  violations?: number
}

export function reconcileExecution(kind: 'ingestion' | 'quality', id: number, data: Reconciliation): Promise<void> {
  return http.post(`/api/${kind}/execution-recovery/${id}/reconcile`, data).then(() => undefined)
}
export function getRecoveryDefinition(kind: 'ingestion' | 'quality', id: number): Promise<{ status: string; executionSnapshot: string }> {
  return http.get(`/api/${kind}/execution-recovery/${id}`).then((res) => res.data)
}
