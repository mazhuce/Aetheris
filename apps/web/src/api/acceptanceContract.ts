/** Public server-verifier read-model. Static summaries never establish current acceptance. */
export const REQUIRED_ACCEPTANCE_GATES = [
  'r1-empty-rebuild', 'r2-cdc', 'r3-managed-lifecycle', 'r4-event-time-quality',
  'r4-r5-control-plane', 'r5-maintenance-runner', 'datasource-nine-local-runtime', 'r3-ha-runtime',
] as const
export const REQUIRED_DATASOURCE_TYPES = [
  'postgresql', 'mysql', 'trino', 'mariadb', 'clickhouse', 'cockroachdb', 'sqlite', 'h2', 'derby',
] as const
export type AcceptanceGateName = typeof REQUIRED_ACCEPTANCE_GATES[number]
export type AcceptanceDatasourceType = typeof REQUIRED_DATASOURCE_TYPES[number]
export type AcceptanceState = 'verified' | 'failed' | 'missing' | 'expired' | 'mismatch' | 'invalid' | 'unavailable'
export type EvidenceResult = 'passed' | 'failed' | 'missing'
export interface AcceptanceBinding {
  runId: string
  contextSha256: string
  source: { commit: string; worktreeSha256: string }
  artifactDigests: Record<string, string>
  configFingerprint: string
  migrationSetSha256: string
  migrationCount: number
}
export interface VerifiedAcceptanceEvidence {
  binding: AcceptanceBinding
  aggregateSha256: string
  manifestSha256: string
  checksSha256: string
  attachmentManifestSha256: string
  gates: { name: AcceptanceGateName; status: EvidenceResult; reportSha256: string; finishedAtUtc: string }[]
  datasourceResults: { type: AcceptanceDatasourceType; status: EvidenceResult; assertionCount: number }[]
}
export interface CurrentAcceptance {
  schemaVersion: 1
  checkedAtUtc: string
  validUntilUtc: string
  state: AcceptanceState
  currentBinding: AcceptanceBinding | null
  evidence: VerifiedAcceptanceEvidence | null
  reasons: string[]
}
export interface HistoricalAcceptance {
  kind: 'platform' | 'control-plane'
  schemaVersion: 1 | 2
  gate: string
  status: 'passed' | 'failed' | 'missing' | 'manual_review_required'
  generatedAtUtc: string
  source: string
}
const SHA256 = /^[a-f0-9]{64}$/
const STATES: AcceptanceState[] = ['verified', 'failed', 'missing', 'expired', 'mismatch', 'invalid', 'unavailable']
const MAX_EVIDENCE_AGE_MS = 24 * 60 * 60 * 1000
const CLOCK_SKEW_MS = 60_000
export class AcceptanceContractError extends Error {
  readonly code = 'INVALID_ACCEPTANCE_RESPONSE'
  constructor() { super('当前验收响应未满足校验契约') }
}
function invalid(): never { throw new AcceptanceContractError() }
function object(value: unknown, keys?: readonly string[]): Record<string, unknown> {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return invalid()
  const body = value as Record<string, unknown>
  if (keys && (Object.keys(body).length !== keys.length || keys.some((key) => !Object.prototype.hasOwnProperty.call(body, key)))) return invalid()
  return body
}
function text(value: unknown, pattern?: RegExp, maxLength = 256): string {
  if (typeof value !== 'string' || !value || value.length > maxLength || (pattern && !pattern.test(value))) return invalid()
  return value
}
function hash(value: unknown) { return text(value, SHA256, 64) }
function positive(value: unknown): number {
  if (typeof value !== 'number' || !Number.isSafeInteger(value) || value < 1) return invalid()
  return value
}
function timestamp(value: unknown): string {
  const result = text(value, undefined, 40)
  const parts = /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2}):(\d{2})(?:\.\d{1,9})?(Z|[+-]\d{2}:\d{2})$/.exec(result)
  if (!parts) return invalid()
  const [, y, m, d, h, minute, second, zone] = parts
  const year = Number(y), month = Number(m), day = Number(d)
  if (year < 1 || month < 1 || month > 12 || day < 1 || day > new Date(Date.UTC(year, month, 0)).getUTCDate()
    || Number(h) > 23 || Number(minute) > 59 || Number(second) > 59
    || (zone !== 'Z' && (Number(zone.slice(1, 3)) > 14 || Number(zone.slice(4)) > 59))
    || !Number.isFinite(Date.parse(result))) return invalid()
  return result
}
function binding(value: unknown): AcceptanceBinding {
  const body = object(value, ['runId', 'contextSha256', 'source', 'artifactDigests', 'configFingerprint', 'migrationSetSha256', 'migrationCount'])
  const source = object(body.source, ['commit', 'worktreeSha256'])
  const artifacts = object(body.artifactDigests), names = Object.keys(artifacts)
  if (!names.length || names.length > 128) return invalid()
  const artifactDigests: Record<string, string> = {}
  for (const name of names) {
    if (!/^[a-z][a-z0-9-]{0,63}$/.test(name)) return invalid()
    artifactDigests[name] = hash(artifacts[name])
  }
  return { runId: text(body.runId, /^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$/, 128), contextSha256: hash(body.contextSha256),
    source: { commit: text(source.commit, /^[a-f0-9]{40}$/, 40), worktreeSha256: hash(source.worktreeSha256) },
    artifactDigests, configFingerprint: hash(body.configFingerprint), migrationSetSha256: hash(body.migrationSetSha256), migrationCount: positive(body.migrationCount) }
}
function sameBinding(left: AcceptanceBinding, right: AcceptanceBinding): boolean {
  const keys = Object.keys(left.artifactDigests).sort()
  return left.runId === right.runId && left.contextSha256 === right.contextSha256
    && left.source.commit === right.source.commit && left.source.worktreeSha256 === right.source.worktreeSha256
    && left.configFingerprint === right.configFingerprint && left.migrationSetSha256 === right.migrationSetSha256
    && left.migrationCount === right.migrationCount && keys.length === Object.keys(right.artifactDigests).length
    && keys.every((key) => right.artifactDigests[key] === left.artifactDigests[key])
}
function evidence(value: unknown, expected: AcceptanceBinding, checkedAt: number): VerifiedAcceptanceEvidence {
  const body = object(value, ['binding', 'aggregateSha256', 'manifestSha256', 'checksSha256', 'attachmentManifestSha256', 'gates', 'datasourceResults'])
  const actualBinding = binding(body.binding)
  if (!sameBinding(expected, actualBinding) || !Array.isArray(body.gates) || body.gates.length !== REQUIRED_ACCEPTANCE_GATES.length
    || !Array.isArray(body.datasourceResults) || body.datasourceResults.length !== REQUIRED_DATASOURCE_TYPES.length) return invalid()
  const seenGates = new Set<string>()
  const gates = body.gates.map((value) => {
    const gate = object(value, ['name', 'status', 'reportSha256', 'finishedAtUtc']), name = text(gate.name)
    if (!REQUIRED_ACCEPTANCE_GATES.includes(name as AcceptanceGateName) || seenGates.has(name) || gate.status !== 'passed') return invalid()
    seenGates.add(name)
    const finishedAtUtc = timestamp(gate.finishedAtUtc), age = checkedAt - Date.parse(finishedAtUtc)
    if (age < -CLOCK_SKEW_MS || age > MAX_EVIDENCE_AGE_MS) return invalid()
    return { name: name as AcceptanceGateName, status: 'passed' as const, reportSha256: hash(gate.reportSha256), finishedAtUtc }
  })
  const seenTypes = new Set<string>()
  const datasourceResults = body.datasourceResults.map((value) => {
    const item = object(value, ['type', 'status', 'assertionCount']), type = text(item.type)
    if (!REQUIRED_DATASOURCE_TYPES.includes(type as AcceptanceDatasourceType) || seenTypes.has(type) || item.status !== 'passed') return invalid()
    seenTypes.add(type)
    return { type: type as AcceptanceDatasourceType, status: 'passed' as const, assertionCount: positive(item.assertionCount) }
  })
  return { binding: actualBinding, aggregateSha256: hash(body.aggregateSha256), manifestSha256: hash(body.manifestSha256),
    checksSha256: hash(body.checksSha256), attachmentManifestSha256: hash(body.attachmentManifestSha256), gates, datasourceResults }
}
/** Server verifies real sources, artifacts and attachments; this enforces its public response contract. */
export function readCurrentAcceptance(value: unknown, now = Date.now()): CurrentAcceptance {
  const body = object(value, ['schemaVersion', 'checkedAtUtc', 'validUntilUtc', 'state', 'currentBinding', 'evidence', 'reasons'])
  if (body.schemaVersion !== 1 || !STATES.includes(body.state as AcceptanceState) || !Array.isArray(body.reasons)
    || body.reasons.length > 16 || !body.reasons.every((reason) => typeof reason === 'string' && /^[A-Z][A-Z0-9_]{0,63}$/.test(reason))) return invalid()
  const checkedAtUtc = timestamp(body.checkedAtUtc), validUntilUtc = timestamp(body.validUntilUtc)
  const checkedAt = Date.parse(checkedAtUtc), validUntil = Date.parse(validUntilUtc)
  if (!Number.isFinite(now) || checkedAt > now + CLOCK_SKEW_MS || validUntil <= checkedAt || validUntil - checkedAt > MAX_EVIDENCE_AGE_MS) return invalid()
  const currentBinding = body.currentBinding === null ? null : binding(body.currentBinding), state = body.state as AcceptanceState
  if (state !== 'verified') {
    if (!body.reasons.length) return invalid()
    return { schemaVersion: 1, state, checkedAtUtc, validUntilUtc, currentBinding, evidence: null, reasons: [...body.reasons] as string[] }
  }
  if (!currentBinding || body.reasons.length) return invalid()
  const verifiedEvidence = evidence(body.evidence, currentBinding, checkedAt)
  if (validUntil > Math.min(...verifiedEvidence.gates.map((gate) => Date.parse(gate.finishedAtUtc) + MAX_EVIDENCE_AGE_MS))) return invalid()
  return acceptanceAtTime({ schemaVersion: 1, state, checkedAtUtc, validUntilUtc, currentBinding, evidence: verifiedEvidence, reasons: [] }, now)
}
/** Also runs when the display lease expires before any new response arrives. */
export function acceptanceAtTime(value: CurrentAcceptance, now = Date.now()): CurrentAcceptance {
  return value.state === 'verified' && Date.parse(value.validUntilUtc) <= now
    ? { ...value, state: 'expired', evidence: null, reasons: ['EVIDENCE_EXPIRED'] } : value
}
export function readHistoricalAcceptance(value: unknown, kind: HistoricalAcceptance['kind']): HistoricalAcceptance {
  const body = object(value)
  if ((body.schemaVersion !== 1 && body.schemaVersion !== 2) || !['passed', 'failed', 'missing', 'manual_review_required'].includes(String(body.status))) return invalid()
  return { kind, schemaVersion: body.schemaVersion, gate: text(body.gate), status: body.status as HistoricalAcceptance['status'],
    generatedAtUtc: timestamp(body.generatedAtUtc), source: text(body.source, undefined, 1024) }
}
