import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import * as api from '@/api/quality'
import QualityRulePage from './index'

vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: (action: string, id: number) => id === 1 && action === 'execute', loading: false }) }))
vi.mock('@/api/quality', () => ({ listRules: vi.fn(), runRule: vi.fn(), updateRule: vi.fn(), createRule: vi.fn(), deleteRule: vi.fn(), getRule: vi.fn(), getRuleApproval: vi.fn(), approveRule: vi.fn(), bindRuleCron: vi.fn() }))
vi.mock('@/api/metadata', () => ({ listDataSources: vi.fn() }))
beforeEach(() => {
  vi.clearAllMocks()
  setAuthenticated({ username: 'operator', role: 'viewer' })
  vi.mocked(api.listRules).mockResolvedValue([1, 2].map((id) => ({ id, name: `规则${id}`, ruleType: 'sql', datasourceId: 3, expression: 'SELECT 0', targetTable: 'warehouse.t' })))
  vi.mocked(api.runRule).mockResolvedValue({ id: 8, ruleId: 1, status: 'passed', startedAt: '2026-10-18T00:00:00Z' })
})
it('offers only the granted fixed-rule execution to a non-admin', async () => {
  render(<App><QualityRulePage /></App>)
  fireEvent.click(await screen.findByRole('button', { name: '检查规则 规则1' }))
  await waitFor(() => expect(api.runRule).toHaveBeenCalledWith(1))
  expect(screen.queryByRole('button', { name: '检查规则 规则2' })).not.toBeInTheDocument()
  expect(screen.queryByRole('button', { name: '更多操作' })).not.toBeInTheDocument()
  expect(api.getRuleApproval).not.toHaveBeenCalled()
})

it('keeps the backend missing-approval reason without silently approving or requiring view permission', async () => {
  vi.mocked(api.runRule).mockRejectedValueOnce({ response: { status: 409, data: { message: '当前执行定义尚未明确批准' } } })
  render(<App><QualityRulePage /></App>); fireEvent.click(await screen.findByRole('button', { name: '检查规则 规则1' }))
  expect(await screen.findByText(/当前执行定义尚未明确批准/)).toBeInTheDocument(); expect(api.getRuleApproval).not.toHaveBeenCalled(); expect(api.approveRule).not.toHaveBeenCalled()
})
