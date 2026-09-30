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
export interface PlatformMilestoneEvidence {
  code: 'R1' | 'R2' | 'R3' | 'R4' | 'R5' | string
  status: AcceptanceGateStatus | 'manual_review_required'
}

export interface PlatformReleaseAcceptanceSummary {
  schemaVersion: number
  gate: string
  status: AcceptanceGateStatus | 'manual_review_required'
  generatedAtUtc: string
  source: string
  milestones: PlatformMilestoneEvidence[]
  datasourceNineStatus: AcceptanceGateStatus | 'manual_review_required'
  externalDatasourceStatus: AcceptanceGateStatus | 'manual_review_required'
  dockerStatus: string
  manualReviewRequired: string[]
}

export async function getPlatformReleaseAcceptance(): Promise<PlatformReleaseAcceptanceSummary> {
  const response = await fetch('/acceptance/platform-release.json', { cache: 'no-store' })
  if (!response.ok) throw new Error(`platform release summary unavailable (${response.status})`)
  return response.json() as Promise<PlatformReleaseAcceptanceSummary>
}