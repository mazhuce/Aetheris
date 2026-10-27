import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App } from 'antd'
import type { TaskDependency, TaskView } from '@/api/workflow'
import WorkflowGraphPage from './index'

const api = vi.hoisted(() => ({ listTasks: vi.fn(), listDependencies: vi.fn(), addDependency: vi.fn(), removeDependency: vi.fn() }))
const notice = vi.hoisted(() => ({ success: vi.fn(), error: vi.fn() }))
vi.mock('@/api/workflow', () => api)
vi.mock('@/utils/access', () => ({ canManagePlatform: () => true }))
vi.mock('@/hooks/useResourceActions', () => ({ useResourceActions: () => ({ can: () => true, loading: false }) }))
// Keep the row action callback, while avoiding a portal/menu gesture in concurrency tests.
vi.mock('@/components/RowActions', () => ({ default: ({ items }: { items: { key: string; label: string; onClick: () => void; disabled?: boolean }[] }) =>
  <>{items.map((item) => <button key={item.key} disabled={item.disabled} onClick={item.onClick}>{item.label}</button>)}</> }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (error: unknown) => void
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail })
  return { promise, resolve, reject }
}
const names = ['Task A', 'Task B', 'Task C', 'Task D', 'Task E']
const tasks = (count = 5): TaskView[] => Array.from({ length: count }, (_, i) => ({ task: { id: i + 1, name: names[i] ?? `Task ${i + 1}`, taskType: 'sql' }, upstreamCount: 0 }))
const dep = (taskId: number, upstreamTaskId: number): TaskDependency => ({ id: taskId * 100 + upstreamTaskId, taskId, upstreamTaskId })
const panel = (name: string) => screen.getByRole('region', { name: `上游依赖 · ${name}` })
const select = (name: string) => fireEvent.click(screen.getByTitle(name))
async function boot() {
  const view = render(<App><WorkflowGraphPage /></App>)
  await waitFor(() => expect(api.listDependencies).toHaveBeenCalledTimes(5))
  await act(async () => { await Promise.resolve() })
  return view
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.spyOn(App, 'useApp').mockReturnValue({ message: notice } as unknown as ReturnType<typeof App.useApp>)
  api.listTasks.mockResolvedValue(tasks())
  api.listDependencies.mockResolvedValue([])
  api.addDependency.mockResolvedValue(dep(1, 3))
  api.removeDependency.mockResolvedValue(undefined)
})

it('an older A detail response cannot overwrite the newer A after A → B → A', async () => {
  await boot()
  const oldA = deferred<TaskDependency[]>()
  const newA = deferred<TaskDependency[]>()
  let reads = 0
  api.listDependencies.mockImplementation((id: number) => id === 1 ? (++reads === 1 ? oldA.promise : newA.promise) : Promise.resolve([dep(2, 4)]))
  select('Task A')
  await waitFor(() => expect(reads).toBe(1))
  select('Task B')
  await within(panel('Task B')).findByText('Task D')
  select('Task A')
  await waitFor(() => expect(reads).toBe(2))
  await act(async () => { newA.resolve([dep(1, 5)]) })
  expect(within(panel('Task A')).getByText('Task E')).toBeInTheDocument()
  await act(async () => { oldA.resolve([dep(1, 3)]) })
  expect(within(panel('Task A')).getByText('Task E')).toBeInTheDocument()
  expect(within(panel('Task A')).queryByText('Task C')).not.toBeInTheDocument()
})

it('switching to B clears A rows and never offers an A row for deletion from B', async () => {
  await boot()
  const pendingB = deferred<TaskDependency[]>()
  api.listDependencies.mockImplementation((id: number) => id === 1 ? Promise.resolve([dep(1, 3)]) : pendingB.promise)
  select('Task A')
  await within(panel('Task A')).findByText('Task C')
  select('Task B')
  const current = within(panel('Task B'))
  const wrongAction = current.queryByRole('button', { name: '移除' })
  if (wrongAction) fireEvent.click(wrongAction)
  expect(api.removeDependency).not.toHaveBeenCalled()
  expect(current.queryByText('Task C')).not.toBeInTheDocument()
  await act(async () => { pendingB.resolve([]) })
})

it.each(['B', 'A again'])('a completed old A deletion cannot replace the details of %s', async (destination) => {
  await boot()
  const deletion = deferred<void>()
  api.removeDependency.mockReturnValue(deletion.promise)
  let oldDeleteFinished = false
  let aReads = 0
  api.listDependencies.mockImplementation((id: number) => {
    if (id === 1) {
      aReads++
      return Promise.resolve([dep(1, oldDeleteFinished ? 3 : aReads === 1 ? 3 : 5)])
    }
    return Promise.resolve(id === 2 ? [dep(2, 4)] : [])
  })
  select('Task A')
  await within(panel('Task A')).findByText('Task C')
  fireEvent.click(within(panel('Task A')).getByRole('button', { name: '移除' }))
  await waitFor(() => expect(api.removeDependency).toHaveBeenCalledWith(1, 3))
  select('Task B')
  await within(panel('Task B')).findByText('Task D')
  if (destination === 'A again') {
    select('Task A')
    await within(panel('Task A')).findByText('Task E')
  }
  oldDeleteFinished = true
  await act(async () => { deletion.resolve(); await Promise.resolve() })
  const target = destination === 'B' ? panel('Task B') : panel('Task A')
  expect(within(target).getByText(destination === 'B' ? 'Task D' : 'Task E')).toBeInTheDocument()
})

it.each([
  ['403', { response: { status: 403 } }],
  ['503', { response: { status: 503 } }],
  ['timeout', { code: 'ECONNABORTED' }]
])('keeps a %s graph failure unknown and retries it without inventing zero dependencies', async (_label, error) => {
  let fail = true
  api.listDependencies.mockImplementation((id: number) => id === 2 && fail ? Promise.reject(error) : Promise.resolve(id === 2 ? [dep(2, 1)] : []))
  await boot()
  expect(screen.getByText(/1 个任务的依赖未知/)).toBeInTheDocument()
  expect(within(screen.getByTitle('Task B')).getByText(/依赖未知/)).toBeInTheDocument()
  fail = false
  fireEvent.click(screen.getByRole('button', { name: '重试未知依赖' }))
  await waitFor(() => expect(screen.queryByText(/1 个任务的依赖未知/)).not.toBeInTheDocument())
  expect(within(screen.getByTitle('Task B')).getByText(/1 上游/)).toBeInTheDocument()
})

it('detail failure is visible as unknown, with a retry, and never as a pipeline root', async () => {
  await boot()
  let fail = true
  api.listDependencies.mockImplementation((id: number) => id === 2 && fail ? Promise.reject({ response: { status: 503 } }) : Promise.resolve([dep(id, 3)]))
  select('Task A')
  await within(panel('Task A')).findByText('Task C')
  select('Task B')
  await within(panel('Task B')).findByText('依赖详情未知')
  expect(within(panel('Task B')).queryByText('Task C')).not.toBeInTheDocument()
  expect(within(panel('Task B')).queryByText(/暂无上游依赖/)).not.toBeInTheDocument()
  fail = false
  fireEvent.click(within(panel('Task B')).getByRole('button', { name: '重试依赖详情' }))
  await within(panel('Task B')).findByText('Task C')
})

it('bounds dependency requests across the graph and selected detail to four active transports', async () => {
  api.listTasks.mockResolvedValue(tasks(16))
  const requests: ReturnType<typeof deferred<TaskDependency[]>>[] = []
  api.listDependencies.mockImplementation(() => { const value = deferred<TaskDependency[]>(); requests.push(value); return value.promise })
  const view = render(<App><WorkflowGraphPage /></App>)
  await waitFor(() => expect(requests.length).toBeGreaterThan(0))
  select('Task A')
  await act(async () => { await Promise.resolve() })
  expect(requests.length).toBeLessThanOrEqual(4)
  view.unmount()
  await act(async () => { requests.forEach((request) => request.resolve([])) })
})

it('unmount aborts in-flight reads and never dispatches queued graph reads', async () => {
  api.listTasks.mockResolvedValue(tasks(12))
  const started: (AbortSignal | undefined)[] = []
  api.listDependencies.mockImplementation((_id: number, signal?: AbortSignal) => {
    started.push(signal)
    return new Promise<TaskDependency[]>((_resolve, reject) => signal?.addEventListener('abort', () => reject(new DOMException('aborted', 'AbortError')), { once: true }))
  })
  const view = render(<App><WorkflowGraphPage /></App>)
  await waitFor(() => expect(started.length).toBeGreaterThan(0))
  const before = started.length
  view.unmount()
  await act(async () => { await Promise.resolve() })
  expect(started.every((signal) => signal?.aborted === true)).toBe(true)
  expect(started.length).toBe(before)
})

it('a superseded graph response cannot erase the latest confirmed edges', async () => {
  api.listTasks.mockResolvedValue(tasks(2))
  const oldA = deferred<TaskDependency[]>()
  let aReads = 0
  let bReads = 0
  api.listDependencies.mockImplementation((id: number) => id === 1 ? (++aReads === 1 ? oldA.promise : Promise.resolve([])) : Promise.resolve(++bReads === 1 ? [] : [dep(2, 1)]))
  render(<App><WorkflowGraphPage /></App>)
  await waitFor(() => expect(aReads).toBe(1))
  fireEvent.click(screen.getByRole('button', { name: /刷\s*新/ }))
  await waitFor(() => expect(within(screen.getByTitle('Task B')).getByText(/1 上游/)).toBeInTheDocument())
  await act(async () => { oldA.resolve([]) })
  expect(within(screen.getByTitle('Task B')).getByText(/1 上游/)).toBeInTheDocument()
})

it('a response carrying another task owner is unknown and cannot expose a remove action', async () => {
  await boot()
  api.listDependencies.mockResolvedValue([dep(2, 3)])
  select('Task A')
  await within(panel('Task A')).findByText('依赖详情未知')
  expect(within(panel('Task A')).queryByRole('button', { name: '移除' })).not.toBeInTheDocument()
  expect(api.removeDependency).not.toHaveBeenCalled()
})

it.each(['indirect ancestor', 'descendants', 'existing direct edge'])('upstream candidates respect %s', async (check) => {
  const rows = new Map([[1, [dep(1, 3)]], [2, [dep(2, 1)]], [3, [dep(3, 5)]], [4, [dep(4, 2)]], [5, []]])
  api.listDependencies.mockImplementation((id: number) => Promise.resolve(rows.get(id) ?? []))
  await boot()
  select('Task A')
  await within(panel('Task A')).findByText('Task C')
  fireEvent.mouseDown(screen.getByRole('combobox'))
  await screen.findByRole('listbox')
  if (check === 'indirect ancestor') expect(screen.getByRole('option', { name: 'Task E' })).toBeInTheDocument()
  if (check === 'descendants') {
    expect(screen.queryByRole('option', { name: 'Task B' })).not.toBeInTheDocument()
    expect(screen.queryByRole('option', { name: 'Task D' })).not.toBeInTheDocument()
  }
  if (check === 'existing direct edge') expect(screen.queryByRole('option', { name: 'Task C' })).not.toBeInTheDocument()
})
