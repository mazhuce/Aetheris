import { REQUIRED_ACCEPTANCE_GATES, REQUIRED_DATASOURCE_TYPES, type CurrentAcceptance } from '@/api/acceptanceContract'

/** Synthetic read-model only; this never claims a real deployment or producer passed. */
export function currentAcceptanceFixture(now = Date.now(), lifetimeMs = 60_000): CurrentAcceptance {
  const binding = {
    runId: 'synthetic-current-run', contextSha256: 'a'.repeat(64),
    source: { commit: 'b'.repeat(40), worktreeSha256: 'c'.repeat(64) },
    artifactDigests: { gateway: 'd'.repeat(64), web: 'e'.repeat(64), workflow: 'f'.repeat(64) },
    configFingerprint: '1'.repeat(64), migrationSetSha256: '2'.repeat(64), migrationCount: 122,
  }
  return {
    schemaVersion: 1, checkedAtUtc: new Date(now).toISOString(), validUntilUtc: new Date(now + lifetimeMs).toISOString(),
    state: 'verified', currentBinding: binding, reasons: [],
    evidence: {
      binding: structuredClone(binding), aggregateSha256: '3'.repeat(64), manifestSha256: '4'.repeat(64),
      checksSha256: '5'.repeat(64), attachmentManifestSha256: '6'.repeat(64),
      gates: REQUIRED_ACCEPTANCE_GATES.map((name, index) => ({ name, status: 'passed', reportSha256: index.toString(16).repeat(64), finishedAtUtc: new Date(now - 1000).toISOString() })),
      datasourceResults: REQUIRED_DATASOURCE_TYPES.map((type) => ({ type, status: 'passed', assertionCount: 3 })),
    },
  }
}
