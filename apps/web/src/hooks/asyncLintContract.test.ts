import { ESLint } from 'eslint'
import { readFileSync } from 'node:fs'
import { describe, expect, it } from 'vitest'

const cwd = process.cwd()
if (JSON.parse(readFileSync(`${cwd}/package.json`, 'utf8')).name !== 'aetheris-web') {
  throw new Error('Run frontend lint contracts from apps/web')
}
const paths = [
  'src/hooks/useSerializedPolling.ts',
  'src/pages/ai/chat/useConversationMessages.ts',
  'src/api/ai.ts',
  'src/api/workflow.ts',
]
const eslint = new ESLint({ cwd })
async function diagnostics(code: string) {
  const [result] = await eslint.lintText(code, { filePath: paths[0] })
  return result.messages.map((message) => message.ruleId)
}

describe('critical asynchronous lint gate', () => {
  it('actually checks all four critical source files with zero warnings', async () => {
    const results = await eslint.lintFiles(paths)
    expect(results).toHaveLength(4)
    for (const result of results) expect(result.messages, result.filePath).toEqual([])
  }, 90_000)

  it('rejects a conditional hook rather than accepting parse-only configuration', async () => {
    expect(await diagnostics(`import { useState } from 'react'
      export function useBroken(enabled: boolean) {
        if (enabled) { const [value] = useState(0); return value }
        return 0
      }`)).toContain('react-hooks/rules-of-hooks')
  })

  it('rejects a stale callback that omits the selected resource from dependencies', async () => {
    expect(await diagnostics(`import { useCallback } from 'react'
      export function useBroken(resourceId: number) {
        return useCallback(() => resourceId, [])
      }`)).toContain('react-hooks/exhaustive-deps')
  })

  it('rejects floating promises including a void bypass', async () => {
    for (const prefix of ['', 'void ']) {
      expect(await diagnostics(`export function load() { ${prefix}Promise.resolve(1) }`))
        .toContain('@typescript-eslint/no-floating-promises')
    }
  })

  it('rejects async callbacks handed to a synchronous void contract', async () => {
    expect(await diagnostics(`export function register(listener: () => void) { listener() }
      register(async () => { await Promise.resolve(1) })`))
      .toContain('@typescript-eslint/no-misused-promises')
  })

  it('accepts an explicitly observed rejection without disabling checks', async () => {
    expect(await diagnostics(`export function load() {
      Promise.resolve(1).catch(() => undefined)
    }`)).toEqual([])
  })
})
