import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { setAuthenticated } from '@/auth/authState'
import * as ingestion from '@/api/ingestion'
import * as quality from '@/api/quality'
import type { ExecutionApproval } from '@/api/executionApproval'
import FixedExecutionApprovalDrawer from './FixedExecutionApprovalDrawer'
vi.mock('@/api/ingestion', () => ({ getTask: vi.fn(), getTaskApproval: vi.fn(), approveTask: vi.fn(), bindTaskCron: vi.fn() }))
vi.mock('@/api/quality', () => ({ getRule: vi.fn(), getRuleApproval: vi.fn(), approveRule: vi.fn(), bindRuleCron: vi.fn() }))
const state: ExecutionApproval = { executionRevision: 7, definitionSha256: 'a'.repeat(64), approved: false, bindingGeneration: 2, scheduleGeneration: 11, cronBound: false }
let server: ExecutionApproval
const task = { id: 1, name: '订单接入', sourceType: 'jdbc', sourceDatasourceId: 81, targetDatasourceId: 82, sourceSql: 'SELECT order_id FROM ods.orders', targetTable: 'warehouse.orders', writeMode: 'replace' as const, batchSize: 200, status: 'enabled', scheduleCron: '0 0 2 * * ?' }
const rule = { id: 1, name: '订单金额检查', ruleType: 'range', datasourceId: 81, targetTable: 'ods.orders', expression: 'amount >= 0', threshold: 2, status: 'enabled', cronExpression: '0 0 2 * * ?' }
const deferred = <T,>() => { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
beforeEach(() => {
  vi.clearAllMocks(); setAuthenticated({ username: 'reviewer', role: 'viewer' }); server = { ...state }
  vi.mocked(ingestion.getTaskApproval).mockImplementation(async () => ({ ...server })); vi.mocked(quality.getRuleApproval).mockImplementation(async () => ({ ...server }))
  vi.mocked(ingestion.getTask).mockResolvedValue(task); vi.mocked(quality.getRule).mockResolvedValue(rule)
  vi.mocked(ingestion.approveTask).mockImplementation(async () => (server = { ...server, approved: true, cronBound: false }))
  vi.mocked(quality.approveRule).mockImplementation(async () => (server = { ...server, approved: true, cronBound: false }))
  vi.mocked(ingestion.bindTaskCron).mockImplementation(async () => (server = { ...server, cronBound: true }))
  vi.mocked(quality.bindRuleCron).mockImplementation(async () => (server = { ...server, cronBound: true }))
})
const props = { kind: 'ingestion' as const, resourceId: 1 as number | null, refreshToken: 0, canApprove: true, canBindCron: true, onClose: vi.fn() }
const view = (overrides = {}) => <App><FixedExecutionApprovalDrawer {...props} {...overrides} /></App>
async function review() { fireEvent.click(await screen.findByRole('checkbox', { name: '我已核对以上已保存的执行定义' })) }
describe('explicit source approval', () => {
  it.each(['ingestion', 'quality'] as const)('reviews fresh %s definition before posting the exact current revision', async (kind) => {
    render(view({ kind })); const button = await screen.findByRole('button', { name: '批准当前版本' }); expect(button).toBeDisabled()
    expect(await screen.findByText(kind === 'ingestion' ? task.sourceSql : rule.expression)).toBeInTheDocument()
    if (kind === 'quality') expect(screen.getByText('违规数阈值').closest('tr')).toHaveTextContent('2')
    await review(); fireEvent.click(button)
    await waitFor(() => expect(kind === 'ingestion' ? ingestion.approveTask : quality.approveRule).toHaveBeenCalledWith(1, 7))
    expect(await screen.findByText('已批准')).toBeInTheDocument(); expect(screen.getByRole('checkbox')).not.toBeChecked()
    expect(ingestion.bindTaskCron).not.toHaveBeenCalled(); expect(quality.bindRuleCron).not.toHaveBeenCalled()
  })
  it('binds the schedule generation rather than binding generation and never silently approves', async () => {
    server = { ...state, approved: true }; render(view()); await review(); fireEvent.click(screen.getByRole('button', { name: '绑定当前账号运行定时任务' }))
    await waitFor(() => expect(ingestion.bindTaskCron).toHaveBeenCalledWith(1, 7, 11)); expect(ingestion.approveTask).not.toHaveBeenCalled(); expect(await screen.findByText('已绑定')).toBeInTheDocument()
    expect(screen.queryByText('reviewer')).not.toBeInTheDocument()
  })
  it('never displays an optimistic approval while the mutation is pending', async () => {
    const pending = deferred<ExecutionApproval>(); vi.mocked(ingestion.approveTask).mockReturnValue(pending.promise)
    render(view()); await review(); fireEvent.click(screen.getByRole('button', { name: '批准当前版本' })); expect(screen.getByText('未批准')).toBeInTheDocument(); expect(screen.queryByText('已批准')).not.toBeInTheDocument()
    await act(async () => { pending.reject({ response: { status: 503 } }) }); expect(await screen.findByText('权限或执行服务暂不可用，请稍后重试')).toBeInTheDocument(); expect(screen.queryByText('已批准')).not.toBeInTheDocument()
  })
  it('keeps the actual safe 409 reason and rereads the new revision without approving it', async () => {
    vi.mocked(ingestion.approveTask).mockImplementation(async () => { server = { ...server, executionRevision: 8, definitionSha256: 'b'.repeat(64) }; throw { response: { status: 409, data: { message: '仍有执行中或待对账记录，禁止重复执行' } } } })
    render(view()); await review(); fireEvent.click(screen.getByRole('button', { name: '批准当前版本' }))
    expect(await screen.findByText(/仍有执行中或待对账记录/)).toBeInTheDocument(); await waitFor(() => expect(screen.getByText('执行版本').closest('tr')).toHaveTextContent('8'))
    expect(screen.getByText('未批准')).toBeInTheDocument(); expect(screen.getByRole('checkbox')).not.toBeChecked(); expect(ingestion.approveTask).toHaveBeenCalledTimes(1)
  })
  it('does not fetch or expose the definition when approval read is forbidden', async () => {
    vi.mocked(ingestion.getTaskApproval).mockRejectedValue({ response: { status: 403 } }); render(view())
    expect(await screen.findByText('没有执行此操作所需的资源权限，请联系授权管理员')).toBeInTheDocument(); expect(ingestion.getTask).not.toHaveBeenCalled(); expect(screen.queryByText(task.sourceSql)).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '批准当前版本' })).not.toBeInTheDocument()
  })
  it('hides mutation controls without update/execute hints and disables binding before approval', async () => {
    const { rerender } = render(view({ canApprove: false, canBindCron: false })); expect(await screen.findByText('未批准')).toBeInTheDocument(); expect(screen.queryByRole('button', { name: '批准当前版本' })).not.toBeInTheDocument(); expect(screen.queryByRole('button', { name: '绑定当前账号运行定时任务' })).not.toBeInTheDocument()
    rerender(view({ canApprove: false })); await review(); expect(screen.getByRole('button', { name: '绑定当前账号运行定时任务' })).toBeDisabled()
  })
  it('refuses a changed sandwich snapshot instead of displaying stale SQL under a new revision', async () => {
    vi.mocked(ingestion.getTaskApproval).mockResolvedValueOnce({ ...state }).mockResolvedValueOnce({ ...state, executionRevision: 8 })
    render(view()); expect(await screen.findByText(/读取期间执行定义或定时状态已变化/)).toBeInTheDocument(); expect(screen.queryByText(task.sourceSql)).not.toBeInTheDocument(); expect(screen.queryByRole('checkbox')).not.toBeInTheDocument()
  })
  it('ignores an old response after switching resources', async () => {
    const old = deferred<ExecutionApproval>(); vi.mocked(ingestion.getTaskApproval).mockImplementation(async (id) => id === 1 ? old.promise : { ...state, executionRevision: 9 })
    vi.mocked(ingestion.getTask).mockResolvedValue({ ...task, id: 2, name: '另一个任务', sourceSql: 'SELECT customer_id FROM ods.customers' })
    const { rerender } = render(view()); await waitFor(() => expect(ingestion.getTaskApproval).toHaveBeenCalledWith(1)); rerender(view({ resourceId: 2 }))
    expect(await screen.findByText('另一个任务')).toBeInTheDocument(); await act(async () => { old.resolve({ ...state }) })
    expect(screen.getByText('另一个任务')).toBeInTheDocument(); expect(screen.queryByText(task.sourceSql)).not.toBeInTheDocument(); expect(ingestion.getTask).not.toHaveBeenCalledWith(1)
  })
  it('reloads and clears the review acknowledgement after definition/cron saves', async () => {
    server = { ...state, approved: true, cronBound: true }; const { rerender } = render(view()); expect(await screen.findByText('已绑定')).toBeInTheDocument(); await review()
    server = { ...state, executionRevision: 8, definitionSha256: 'c'.repeat(64), scheduleGeneration: 12 }; vi.mocked(ingestion.getTask).mockResolvedValue({ ...task, sourceSql: 'SELECT updated_id FROM ods.orders' }); rerender(view({ refreshToken: 1 }))
    expect(await screen.findByText('SELECT updated_id FROM ods.orders')).toBeInTheDocument(); expect(screen.getByText('未批准')).toBeInTheDocument(); expect(screen.getByText('未绑定')).toBeInTheDocument(); expect(screen.getByRole('checkbox')).not.toBeChecked()
  })
  it('does not apply a prior account mutation after an account switch', async () => {
    const old = deferred<ExecutionApproval>(); vi.mocked(ingestion.approveTask).mockReturnValue(old.promise); render(view()); await review(); fireEvent.click(screen.getByRole('button', { name: '批准当前版本' }))
    await act(async () => { setAuthenticated({ username: 'another', role: 'viewer' }) }); await screen.findByText('未批准')
    await act(async () => { old.resolve({ ...state, approved: true }) }); expect(screen.queryByText('已批准')).not.toBeInTheDocument(); expect(screen.getByText('未批准')).toBeInTheDocument()
  })
  it('ignores the original POST even when navigation returns to the same resource', async () => {
    const old = deferred<ExecutionApproval>(); vi.mocked(ingestion.approveTask).mockReturnValue(old.promise)
    vi.mocked(ingestion.getTask).mockImplementation(async (id) => ({ ...task, id, name: id === 1 ? '任务A' : '任务B' }))
    const { rerender } = render(view()); await review(); fireEvent.click(screen.getByRole('button', { name: '批准当前版本' }))
    rerender(view({ resourceId: 2 })); expect(await screen.findByText('任务B')).toBeInTheDocument()
    rerender(view({ resourceId: 1 })); expect(await screen.findByText('任务A')).toBeInTheDocument(); await review()
    const reads = vi.mocked(ingestion.getTaskApproval).mock.calls.length
    await act(async () => { old.resolve({ ...state, approved: true }) })
    expect(ingestion.getTaskApproval).toHaveBeenCalledTimes(reads); expect(screen.getByRole('checkbox')).toBeChecked(); expect(screen.queryByText('已批准')).not.toBeInTheDocument()
  })

})
