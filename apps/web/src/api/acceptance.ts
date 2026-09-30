export type AcceptanceGateStatus = 'passed' | 'failed' | 'missing'

export interface SchemaPolicyEvidence {
  decision: 'approval_required' | 'isolate' | string
  status: string
  readyForExecution?: boolean
  destructive?: boolean
  planRejected?: boolean
}

export interface ControlPlaneAcceptanceSummary {
  schemaVersion: number
  gate: string
  status: AcceptanceGateStatus
  generatedAtUtc: string
  source: string
  quality: {
    blockedOnFailure: boolean
    reopenedAfterPass: boolean
  }
  schemaPolicy: {
    rename: SchemaPolicyEvidence
    drop: SchemaPolicyEvidence
    narrow: SchemaPolicyEvidence
  }
}

export async function getControlPlaneAcceptance(): Promise<ControlPlaneAcceptanceSummary> {
  const response = await fetch('/acceptance/r4-r5-control-plane.json', { cache: 'no-store' })
  if (!response.ok) throw new Error(`acceptance summary unavailable (${response.status})`)
  return response.json() as Promise<ControlPlaneAcceptanceSummary>
}