import { act, fireEvent, render, screen, within } from '@testing-library/react'
import { App } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as metadata from '@/api/metadata'
import type { DataSourceColumn, MetadataTable } from '@/api/metadata'
import TableBrowserPage from './index'

const tables = vi.hoisted(() => [
  { id: 1, datasourceId: 11, datasourceName: 'source-A', schemaName: 'schema-A', tableName: 'table-A' },
  { id: 2, datasourceId: 22, datasourceName: 'source-B', schemaName: 'schema-B', tableName: 'table-B' },
  { id: 3, datasourceName: 'unlinked', tableName: 'table-unlinked' }
] as MetadataTable[])

vi.mock('@/api/metadata', () => ({
  listTablesPage: vi.fn(), listTableCatalog: vi.fn(),
  listDataSourceColumns: vi.fn(), previewDataSourceTable: vi.fn()
}))
vi.mock('@/hooks/useVisibleListPage', () => ({ useVisibleListPage: () => ({
  items: tables, loading: false, error: undefined, pageNumber: 1, hasMore: false,
  canPrevious: false, next: vi.fn(), previous: vi.fn(), refresh: vi.fn()
}) }))
vi.mock('@/hooks/useResponsivePageSize', () => ({ useResponsivePageSize: () => 20 }))
vi.mock('@/components/PageHeader', () => ({ default: () => null }))
vi.mock('@/components/PageToolbar', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))
vi.mock('@/components/DataSurface', () => ({ default: ({ children }: { children: React.ReactNode }) => <div>{children}</div> }))

function deferred<T>() {
  let resolve!: (value: T) => void
  let reject!: (reason: unknown) => void
  const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}

function pendingDetail() {
  return {
    columns: deferred<DataSourceColumn[]>(),
    preview: deferred<Awaited<ReturnType<typeof metadata.previewDataSourceTable>>>()
  }
}

async function complete(detail: ReturnType<typeof pendingDetail>, label: string) {
  await act(async () => {
    detail.columns.resolve([{ name: `column-${label}`, type: 'VARCHAR' }])
    detail.preview.resolve({ columns: ['value'], rows: [[`preview-${label}`]], rowCount: 1, truncated: false })
  })
}

function open(name: string) {
  fireEvent.click(within(screen.getByText(name).closest('tr')!).getByRole('button', { name: '查看详情' }))
}

function showPreview() {
  fireEvent.click(screen.getByRole('tab', { name: /数据预览/ }))
}

beforeEach(() => {
  vi.resetAllMocks()
  vi.mocked(metadata.listTableCatalog).mockResolvedValue([])
})

describe('table asset detail belongs to the selected request instance', () => {
  it('discards late A fields and preview after switching to B', async () => {
    const a = pendingDetail(), b = pendingDetail()
    vi.mocked(metadata.listDataSourceColumns).mockReturnValueOnce(a.columns.promise).mockReturnValueOnce(b.columns.promise)
    vi.mocked(metadata.previewDataSourceTable).mockReturnValueOnce(a.preview.promise).mockReturnValueOnce(b.preview.promise)
    render(<App><TableBrowserPage /></App>)
    open('table-A'); open('table-B')
    await complete(b, 'B')
    await complete(a, 'late-A')
    expect(screen.getByText('table-B · 表详情')).toBeInTheDocument()
    expect(screen.getByText('column-B')).toBeInTheDocument()
    expect(screen.queryByText('column-late-A')).not.toBeInTheDocument()
    showPreview()
    expect(await screen.findByText('preview-B')).toBeInTheDocument()
    expect(screen.queryByText('preview-late-A')).not.toBeInTheDocument()
  })

  it('distinguishes a fresh A selection from both earlier A and B in A→B→A', async () => {
    const firstA = pendingDetail(), b = pendingDetail(), currentA = pendingDetail()
    for (const detail of [firstA, b, currentA]) {
      vi.mocked(metadata.listDataSourceColumns).mockReturnValueOnce(detail.columns.promise)
      vi.mocked(metadata.previewDataSourceTable).mockReturnValueOnce(detail.preview.promise)
    }
    render(<App><TableBrowserPage /></App>)
    open('table-A'); open('table-B'); open('table-A')
    await complete(currentA, 'current-A')
    await complete(b, 'late-B'); await complete(firstA, 'old-A')
    expect(screen.getByText('column-current-A')).toBeInTheDocument()
    expect(screen.queryByText('column-old-A')).not.toBeInTheDocument()
    showPreview()
    expect(await screen.findByText('preview-current-A')).toBeInTheDocument()
  })

  it('invalidates closed requests before reopening another table', async () => {
    const a = pendingDetail(), b = pendingDetail()
    vi.mocked(metadata.listDataSourceColumns).mockReturnValueOnce(a.columns.promise).mockReturnValueOnce(b.columns.promise)
    vi.mocked(metadata.previewDataSourceTable).mockReturnValueOnce(a.preview.promise).mockReturnValueOnce(b.preview.promise)
    render(<App><TableBrowserPage /></App>)
    open('table-A')
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    open('table-B')
    await complete(b, 'B'); await complete(a, 'closed-A')
    expect(screen.getByText('column-B')).toBeInTheDocument()
    expect(screen.queryByText('column-closed-A')).not.toBeInTheDocument()
  })

  it.each([
    [{ response: { status: 403 }, message: 'secret jdbc password' }, '无权读取字段结构'],
    [{ response: { status: 503 }, message: 'secret jdbc password' }, '字段结构服务暂时不可用'],
    [{ code: 'ECONNABORTED', message: 'secret jdbc password' }, '字段结构加载超时']
  ])('shows a safe retryable field error instead of an empty field list: %j', async (error, expected) => {
    vi.mocked(metadata.listDataSourceColumns).mockRejectedValueOnce(error)
      .mockResolvedValueOnce([{ name: 'recovered-column', type: 'INT' }])
    vi.mocked(metadata.previewDataSourceTable).mockResolvedValue({ columns: ['value'], rows: [['real-preview']], rowCount: 1, truncated: false })
    render(<App><TableBrowserPage /></App>)
    open('table-A')
    expect(await screen.findByText(expected)).toBeInTheDocument()
    expect(screen.queryByText(/secret jdbc password/)).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试字段结构' }))
    expect(await screen.findByText('recovered-column')).toBeInTheDocument()
    expect(metadata.listDataSourceColumns).toHaveBeenLastCalledWith(11, 'schema-A', 'table-A', { signal: expect.any(AbortSignal) })
    showPreview()
    expect(await screen.findByText('real-preview')).toBeInTheDocument()
  })

  it('retries only the current table preview and preserves successful fields', async () => {
    const a = pendingDetail()
    vi.mocked(metadata.listDataSourceColumns).mockReturnValueOnce(a.columns.promise)
      .mockResolvedValueOnce([{ name: 'column-B', type: 'INT' }])
    vi.mocked(metadata.previewDataSourceTable).mockReturnValueOnce(a.preview.promise)
      .mockRejectedValueOnce({ response: { status: 503 } })
      .mockResolvedValueOnce({ columns: ['value'], rows: [['recovered-B']], rowCount: 1, truncated: false })
    render(<App><TableBrowserPage /></App>)
    open('table-A'); open('table-B')
    await act(async () => {
      a.columns.resolve([{ name: 'old-A', type: 'INT' }])
      a.preview.reject({ response: { status: 403 } })
    })
    expect(await screen.findByText('column-B')).toBeInTheDocument()
    showPreview()
    expect(await screen.findByText('数据预览服务暂时不可用')).toBeInTheDocument()
    expect(screen.queryByText('无权读取数据预览')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: '重试数据预览' }))
    expect(await screen.findByText('recovered-B')).toBeInTheDocument()
    expect(metadata.previewDataSourceTable).toHaveBeenLastCalledWith(22, 'schema-B', 'table-B', { signal: expect.any(AbortSignal) })
    expect(metadata.listDataSourceColumns).toHaveBeenCalledTimes(2)
    fireEvent.click(screen.getByRole('tab', { name: /字段结构/ }))
    expect(screen.getByText('column-B')).toBeInTheDocument()
  })

  it('presents successful empty results separately from failed reads', async () => {
    vi.mocked(metadata.listDataSourceColumns).mockResolvedValue([])
    vi.mocked(metadata.previewDataSourceTable).mockResolvedValue({ columns: ['id'], rows: [], rowCount: 0, truncated: false })
    render(<App><TableBrowserPage /></App>)
    open('table-A')
    expect(await screen.findByText('暂无字段')).toBeInTheDocument()
    showPreview()
    expect(await screen.findByText('暂无样例数据')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('does not offer duplicate retries while the current reads are in flight', async () => {
    const pending = pendingDetail()
    vi.mocked(metadata.listDataSourceColumns).mockReturnValue(pending.columns.promise)
    vi.mocked(metadata.previewDataSourceTable).mockReturnValue(pending.preview.promise)
    render(<App><TableBrowserPage /></App>)
    open('table-A')
    expect(screen.queryByRole('button', { name: '重试字段结构' })).not.toBeInTheDocument()
    showPreview()
    expect(screen.queryByRole('button', { name: '重试数据预览' })).not.toBeInTheDocument()
    expect(metadata.listDataSourceColumns).toHaveBeenCalledTimes(1)
    expect(metadata.previewDataSourceTable).toHaveBeenCalledTimes(1)
    await complete(pending, 'current')
    expect(await screen.findByText('preview-current')).toBeInTheDocument()
  })

  it('aborts reads on close and unmount and discards late rejected promises', async () => {
    const pending = pendingDetail()
    vi.mocked(metadata.listDataSourceColumns).mockReturnValue(pending.columns.promise)
    vi.mocked(metadata.previewDataSourceTable).mockReturnValue(pending.preview.promise)
    const view = render(<App><TableBrowserPage /></App>)
    open('table-A')
    const firstSignals = [
      vi.mocked(metadata.listDataSourceColumns).mock.calls[0][3]?.signal,
      vi.mocked(metadata.previewDataSourceTable).mock.calls[0][3]?.signal
    ]
    expect(firstSignals.every((signal) => signal instanceof AbortSignal && !signal.aborted)).toBe(true)
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(firstSignals.every((signal) => signal?.aborted)).toBe(true)
    open('table-A')
    const secondSignals = [
      vi.mocked(metadata.listDataSourceColumns).mock.calls[1][3]?.signal,
      vi.mocked(metadata.previewDataSourceTable).mock.calls[1][3]?.signal
    ]
    expect(secondSignals.every((signal) => signal instanceof AbortSignal && !signal.aborted)).toBe(true)
    view.unmount()
    expect(secondSignals.every((signal) => signal?.aborted)).toBe(true)
    await act(async () => {
      pending.columns.reject({ response: { status: 403 } })
      pending.preview.reject({ response: { status: 503 } })
    })
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('explains an unlinked table without attempting a datasource call', async () => {
    render(<App><TableBrowserPage /></App>)
    open('table-unlinked')
    expect(await screen.findByText('未关联数据源，无法加载详情')).toBeInTheDocument()
    expect(metadata.listDataSourceColumns).not.toHaveBeenCalled()
    expect(metadata.previewDataSourceTable).not.toHaveBeenCalled()
  })
})
