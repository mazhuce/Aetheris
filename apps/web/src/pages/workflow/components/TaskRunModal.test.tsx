import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { expect, it, vi } from 'vitest'
import { getBusinessCalendar, runTask, type TaskView } from '@/api/workflow'
import TaskRunModal from './TaskRunModal'

vi.mock('@/api/workflow', () => ({ getBusinessCalendar: vi.fn(), runTask: vi.fn(), INSTANCE_STATUS_META: { unknown: { label: '待核对' } } }))
it('uses the server business date and keeps unknown execution visible for reconciliation', async () => {
  vi.mocked(getBusinessCalendar).mockResolvedValue({ zone: 'America/Los_Angeles', today: '2024-02-29', previousDate: '2024-02-28' })
  vi.mocked(runTask).mockResolvedValue({ id: 8, taskId: 1, bizDate: '2024-02-28', status: 'unknown' })
  const target = { task: { id: 1, name: '任务' } } as TaskView
  const closed = vi.fn()
  render(<App><TaskRunModal target={target} onClose={closed} onFinished={vi.fn()} /></App>)
  await waitFor(() => expect(screen.getByLabelText('业务日期')).toHaveValue('2024-02-28'))
  fireEvent.click(screen.getByRole('button', { name: '开始执行' }))
  await waitFor(() => expect(runTask).toHaveBeenCalledWith(1, '2024-02-28'))
  expect(await screen.findByText('待核对')).toBeInTheDocument()
  expect(screen.queryByText('任务执行失败，请查看日志')).not.toBeInTheDocument()
  expect(closed).not.toHaveBeenCalled()
})
