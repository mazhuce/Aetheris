import { expect, it } from 'vitest'
import { executionApprovalError } from './executionApprovalError'
it('keeps safe state-conflict evidence rather than claiming every 409 means missing approval', () => {
  expect(executionApprovalError({ response: { status: 409, data: { message: 'unknown结果仍占用执行槽位' } } })).toContain('unknown结果仍占用执行槽位')
  expect(executionApprovalError({ response: { status: 409 } })).toContain('待对账占槽')
  expect(executionApprovalError({ response: { status: 403 } })).toContain('资源权限')
  expect(executionApprovalError({ response: { status: 503 } })).toContain('暂不可用')
  expect(executionApprovalError({ response: { status: 409, data: { message: 'x'.repeat(301) } } })).not.toContain('x'.repeat(301))
})
