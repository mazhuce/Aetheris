import { beforeEach, describe, expect, it, vi } from 'vitest'
import http from './http'
import { readExecutionApproval, approvalMutationConfig } from './executionApproval'
import { getTaskApproval, approveTask, bindTaskCron } from './ingestion'
import { getRuleApproval, approveRule, bindRuleCron } from './quality'
vi.mock('./http', () => ({ default: { get: vi.fn(), post: vi.fn() } }))
const state = { executionRevision: 7, definitionSha256: 'a'.repeat(64), approved: false, bindingGeneration: 2, scheduleGeneration: 11, cronBound: false }
beforeEach(() => { vi.clearAllMocks(); vi.mocked(http.get).mockResolvedValue({ data: state }); vi.mocked(http.post).mockResolvedValue({ data: state }) })
describe('fixed execution approval wire contract', () => {
  it.each([['ingestion/tasks', getTaskApproval, approveTask, bindTaskCron], ['quality/rules', getRuleApproval, approveRule, bindRuleCron]] as const)('sends only revision/generation for %s with no identity replay', async (path, get, approve, bind) => {
    await expect(get(3)).resolves.toEqual(state); expect(http.get).toHaveBeenCalledWith(`/api/${path}/3/approval`)
    await approve(3, 7); expect(http.post).toHaveBeenCalledWith(`/api/${path}/3/approve`, { expectedRevision: 7 }, approvalMutationConfig)
    await bind(3, 7, 11); expect(http.post).toHaveBeenCalledWith(`/api/${path}/3/cron-binding`, { expectedRevision: 7, expectedGeneration: 11 }, approvalMutationConfig)
    expect(approvalMutationConfig).toEqual({ _aetherisRetried: true })
  })
  it('accepts real initial generation zero and rejects malformed or overbroad states', () => {
    expect(readExecutionApproval({ ...state, scheduleGeneration: 0 }).scheduleGeneration).toBe(0)
    for (const invalid of [{ ...state, actor: 'admin' }, { ...state, executionRevision: '7' }, { ...state, executionRevision: 0 }, { ...state, definitionSha256: 'short' }, { ...state, approved: 'true' }, { ...state, bindingGeneration: -1 }, { ...state, scheduleGeneration: Number.MAX_SAFE_INTEGER + 1 }, { ...state, cronBound: 1 }]) expect(() => readExecutionApproval(invalid)).toThrow()
  })
  it('does not turn rejected mutations or invalid success schemas into approved state', async () => {
    const conflict = { response: { status: 409 } }; vi.mocked(http.post).mockRejectedValueOnce(conflict)
    await expect(approveTask(3, 7)).rejects.toBe(conflict)
    vi.mocked(http.post).mockResolvedValueOnce({ data: { approved: true } }); await expect(approveRule(3, 7)).rejects.toThrow()
  })
})
