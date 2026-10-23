import type { AxiosRequestConfig } from 'axios'

/** Current server approval state; subject identities are intentionally not exposed. */
export interface ExecutionApproval {
  executionRevision: number
  definitionSha256: string
  approved: boolean
  bindingGeneration: number
  scheduleGeneration: number
  cronBound: boolean
}

export function readExecutionApproval(value: unknown): ExecutionApproval {
  const fields = ['executionRevision', 'definitionSha256', 'approved', 'bindingGeneration', 'scheduleGeneration', 'cronBound']
  if (!value || typeof value !== 'object' || Object.keys(value).length !== fields.length || fields.some((field) => !Object.prototype.hasOwnProperty.call(value, field))) throw new Error('执行批准状态格式无效')
  const state = value as ExecutionApproval
  if (!Number.isSafeInteger(state.executionRevision) || state.executionRevision < 1 || typeof state.definitionSha256 !== 'string' || !/^[a-f0-9]{64}$/.test(state.definitionSha256)
    || !Number.isSafeInteger(state.bindingGeneration) || state.bindingGeneration < 0 || !Number.isSafeInteger(state.scheduleGeneration) || state.scheduleGeneration < 0
    || typeof state.approved !== 'boolean' || typeof state.cronBound !== 'boolean') throw new Error('执行批准状态格式无效')
  return state
}

/** A failed identity check must require a new explicit approval, never an automatic retry under another session. */
export const approvalMutationConfig: AxiosRequestConfig & { _aetherisRetried: true } = { _aetherisRetried: true }
