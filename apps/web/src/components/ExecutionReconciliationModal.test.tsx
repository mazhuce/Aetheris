import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, expect, it, vi } from 'vitest'
import ExecutionReconciliationModal from './ExecutionReconciliationModal'
import { getRecoveryDefinition, reconcileExecution } from '@/api/executionRecovery'

vi.mock('@/api/executionRecovery', () => ({ reconcileExecution: vi.fn(), getRecoveryDefinition: vi.fn() }))
beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(getRecoveryDefinition).mockResolvedValue({ status: 'unknown', executionSnapshot: '{"targetTable":"warehouse.t"}' })
})

it('requires an explicit stopped-execution confirmation and keeps server failures reviewable', async () => {
  const close = vi.fn()
  const resolved = vi.fn()
  render(<App><ExecutionReconciliationModal kind="ingestion" id={9} onClose={close} onResolved={resolved} /></App>)
  fireEvent.mouseDown(screen.getByRole('combobox'))
  fireEvent.click(await screen.findByText('确认未成功'))
  fireEvent.change(screen.getByLabelText('对账依据'), { target: { value: '核实远端作业与目标库事务，确认均已终止并无本次写入。' } })
  fireEvent.click(screen.getByText('保存对账结果'))
  await screen.findByText('请先确认旧执行已结束')
  expect(reconcileExecution).not.toHaveBeenCalled()

  vi.mocked(reconcileExecution).mockRejectedValue({ response: { data: { message: '记录已由其他管理员对账' } } })
  fireEvent.click(screen.getByRole('checkbox'))
  await act(async () => fireEvent.click(screen.getByText('保存对账结果')))
  await screen.findByText('记录已由其他管理员对账')
  expect(close).not.toHaveBeenCalled()
  expect(resolved).not.toHaveBeenCalled()
  expect(reconcileExecution).toHaveBeenCalledWith('ingestion', 9, expect.objectContaining({ status: 'failed', confirmedRemoteStopped: true }))

  vi.mocked(reconcileExecution).mockResolvedValue(undefined)
  fireEvent.click(screen.getByText('保存对账结果'))
  await waitFor(() => expect(resolved).toHaveBeenCalledTimes(1))
  expect(close).toHaveBeenCalledTimes(1)
})
