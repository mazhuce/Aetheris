import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import { listPolicies, updatePolicy } from '@/api/governance'
import GovernancePage from './index'

vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: (action: string, id: number) => action === 'update' && id === 1, loading: false }) }))
vi.mock('@/api/governance', () => ({ listPolicies: vi.fn(), updatePolicy: vi.fn(), createPolicy: vi.fn(), deletePolicy: vi.fn() }))

it('allows a delegated rename while keeping the policy definition protected', async () => {
  setAuthenticated({ id: 15, accessVersion: 0, permissionVersion: 0, username: 'operator', role: 'viewer' })
  vi.mocked(listPolicies).mockResolvedValue([1, 2].map((id) => ({ id, name: `策略${id}`, policyType: 'masking', targetTable: 'warehouse.orders', ruleExpression: 'phone', status: 'enabled' })))
  render(<App><GovernancePage /></App>)
  fireEvent.click(await screen.findByRole('button', { name: '更多操作' }))
  fireEvent.click(await screen.findByText('编辑'))
  expect(screen.getByLabelText('目标表')).toBeDisabled()
  expect(screen.getByLabelText('规则表达式')).toBeDisabled()
  const name = screen.getByLabelText('策略名称')
  fireEvent.change(name, { target: { value: '改名' } })
  fireEvent.submit(name.closest('form')!)
  await waitFor(() => expect(updatePolicy).toHaveBeenCalledWith(1, { name: '改名', description: undefined, status: 'enabled' }))
})
