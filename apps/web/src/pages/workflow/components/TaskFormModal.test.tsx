import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { expect, it, vi } from 'vitest'
import { updateTask, listSparkJobs, type TaskView } from '@/api/workflow'
import { listDataSources } from '@/api/metadata'
import TaskFormModal from './TaskFormModal'

vi.mock('@/api/workflow', () => ({ updateTask: vi.fn(), createTask: vi.fn(), listSparkJobs: vi.fn(), probeFlinkHealth: vi.fn() }))
vi.mock('@/api/metadata', () => ({ listDataSources: vi.fn() }))
vi.mock('@/api/quality', () => ({ listRules: vi.fn() }))

it('sends only delegated fields without loading or submitting the execution definition', async () => {
  const editing = { task: { id: 1, name: '固定加工', taskType: 'sql', datasourceId: 4, taskSql: 'INSERT INTO target SELECT 1', status: 'disabled' } } as TaskView
  render(<App><TaskFormModal open editing={editing} canEditDefinition={false} canExecute={false} onClose={vi.fn()} onSaved={vi.fn()} /></App>)
  const name = await screen.findByLabelText('任务名称')
  expect(screen.getByLabelText('加工 SQL')).toBeDisabled()
  expect(screen.getByLabelText('调度表达式')).toBeDisabled()
  expect(listDataSources).not.toHaveBeenCalled()
  expect(listSparkJobs).not.toHaveBeenCalled()
  fireEvent.change(name, { target: { value: '展示名称' } })
  fireEvent.submit(name.closest('form')!)
  await waitFor(() => expect(updateTask).toHaveBeenCalledWith(1, { name: '展示名称', description: undefined, status: 'disabled', cronExpression: undefined }))
})
