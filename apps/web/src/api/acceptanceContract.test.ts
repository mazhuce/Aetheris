import { describe, expect, it } from 'vitest'
import { acceptanceAtTime, readCurrentAcceptance, readHistoricalAcceptance } from './acceptanceContract'
import { currentAcceptanceFixture } from '@/pages/development/acceptance/testFixtures'

const now = Date.parse('2026-10-26T10:00:00Z')
function altered(change: (value: Record<string, any>) => void) {
  const value = structuredClone(currentAcceptanceFixture(now))
  change(value)
  return value
}
describe('current acceptance server read-model', () => {
  it('accepts complete matching binding and business assertion counts without a fixed row count', () => {
    expect(readCurrentAcceptance(currentAcceptanceFixture(now), now).state).toBe('verified')
    const reversed = altered((value) => { value.evidence.binding.artifactDigests = Object.fromEntries(Object.entries(value.evidence.binding.artifactDigests).reverse()) })
    expect(readCurrentAcceptance(reversed, now).state).toBe('verified')
  })
  it.each([
    ['old schema', (v: any) => { v.schemaVersion = 2 }],
    ['missing field', (v: any) => { delete v.currentBinding }],
    ['extra field', (v: any) => { v.passed = true }],
    ['missing authority', (v: any) => { v.currentBinding = null }],
    ['missing evidence', (v: any) => { v.evidence = null }],
    ['run', (v: any) => { v.evidence.binding.runId = 'other' }],
    ['context', (v: any) => { v.evidence.binding.contextSha256 = '9'.repeat(64) }],
    ['commit', (v: any) => { v.evidence.binding.source.commit = '9'.repeat(40) }],
    ['worktree', (v: any) => { v.evidence.binding.source.worktreeSha256 = '9'.repeat(64) }],
    ['artifact changed', (v: any) => { v.evidence.binding.artifactDigests.workflow = '9'.repeat(64) }],
    ['artifact omitted', (v: any) => { delete v.evidence.binding.artifactDigests.workflow }],
    ['empty artifact set', (v: any) => { v.currentBinding.artifactDigests = {}; v.evidence.binding.artifactDigests = {} }],
    ['configuration', (v: any) => { v.evidence.binding.configFingerprint = '9'.repeat(64) }],
    ['migration hash', (v: any) => { v.evidence.binding.migrationSetSha256 = '9'.repeat(64) }],
    ['migration count', (v: any) => { v.evidence.binding.migrationCount += 1 }],
    ['attachment reference missing', (v: any) => { delete v.evidence.attachmentManifestSha256 }],
    ['bad attachment digest', (v: any) => { v.evidence.attachmentManifestSha256 = 'short' }],
    ['gate missing', (v: any) => { v.evidence.gates.pop() }],
    ['gate duplicate', (v: any) => { v.evidence.gates[1] = v.evidence.gates[0] }],
    ['gate failed', (v: any) => { v.evidence.gates[0].status = 'failed' }],
    ['gate unknown', (v: any) => { v.evidence.gates[0].name = 'another-gate' }],
    ['gate report replaced by flag', (v: any) => { v.evidence.gates[0].reportSha256 = true }],
    ['gate from old run time', (v: any) => { v.evidence.gates[0].finishedAtUtc = '2026-10-01T10:00:00Z' }],
    ['datasource missing', (v: any) => { v.evidence.datasourceResults.pop() }],
    ['datasource duplicate', (v: any) => { v.evidence.datasourceResults[1] = v.evidence.datasourceResults[0] }],
    ['datasource failed', (v: any) => { v.evidence.datasourceResults[0].status = 'failed' }],
    ['no business assertion', (v: any) => { v.evidence.datasourceResults[0].assertionCount = 0 }],
    ['unsafe count', (v: any) => { v.evidence.datasourceResults[0].assertionCount = Number.MAX_SAFE_INTEGER + 1 }],
    ['future response', (v: any) => { v.checkedAtUtc = '2026-10-27T10:00:00Z' }],
    ['invalid calendar', (v: any) => { v.checkedAtUtc = '2026-02-30T10:00:00Z' }],
    ['unbounded validity', (v: any) => { v.validUntilUtc = '2026-11-01T10:00:00Z' }],
    ['success with a failure reason', (v: any) => { v.reasons = ['ATTACHMENT_MISSING'] }],
  ])('rejects %s rather than trusting verified or passed flags', (_, change) => {
    expect(() => readCurrentAcceptance(altered(change), now)).toThrow()
  })
  it('expires on read and during display without retaining per-gate successes', () => {
    const value = currentAcceptanceFixture(now, 1000)
    expect(readCurrentAcceptance(value, now + 1001)).toMatchObject({ state: 'expired', evidence: null })
    expect(acceptanceAtTime(readCurrentAcceptance(value, now), now + 1001)).toMatchObject({ state: 'expired', evidence: null })
  })
  it.each(['failed', 'missing', 'expired', 'mismatch', 'invalid', 'unavailable'] as const)('never promotes the server %s state using nested old successes', (state) => {
    const value = currentAcceptanceFixture(now)
    value.state = state; value.reasons = ['SERVER_EVIDENCE_NOT_VERIFIED']
    expect(readCurrentAcceptance(value, now)).toMatchObject({ state, evidence: null })
  })
  it('keeps both legacy schema versions explicitly historical and validates basic shape', () => {
    const legacy = { schemaVersion: 1, gate: 'platform-release-acceptance', status: 'passed', generatedAtUtc: '2026-10-03T10:00:00Z', source: 'old/report.json' }
    expect(readHistoricalAcceptance(legacy, 'platform')).toMatchObject({ kind: 'platform', schemaVersion: 1, status: 'passed' })
    expect(readHistoricalAcceptance({ ...legacy, schemaVersion: 2 }, 'platform').schemaVersion).toBe(2)
    expect(() => readCurrentAcceptance(legacy, now)).toThrow()
    expect(() => readHistoricalAcceptance({ ...legacy, status: true }, 'platform')).toThrow()
  })
})
