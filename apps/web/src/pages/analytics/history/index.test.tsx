import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as metadata from '@/api/metadata'
import type { QueryHistoryRecord } from '@/api/metadata'
import QueryHistoryPage from './index'

vi.mock('@/api/metadata')

const own: QueryHistoryRecord = {
  id: 7, username: 'alice', datasourceId: 5, datasourceName: '业务库',
  sqlText: "SELECT 'own-private-sql'", status: 'success', requestId: 'verified-request-7'
}

function page() {
  return render(<MemoryRouter><AntdApp><QueryHistoryPage /></AntdApp></MemoryRouter>)
}

async function clearBatch() {
  fireEvent.click(screen.getByRole('button', { name: /清理历史/ }))
  fireEvent.click(await screen.findByRole('button', { name: /清理本批/ }))
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(metadata.listQueryHistory).mockResolvedValue([{ ...own }])
})

describe('query history ownership and bounded clearing presentation', () => {
  it('keeps own SQL copyable and displays the verified correlation id', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined)
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText } })
    page()
    await screen.findByText('请求编号：verified-request-7')
    fireEvent.click(screen.getByRole('button', { name: '复制查询 7 的 SQL' }))
    await waitFor(() => expect(writeText).toHaveBeenCalledWith(own.sqlText))
    expect(screen.getByText(/最多展示 200 条/)).toBeInTheDocument()
  })

  it('does not claim all history is cleared when the server reports another batch', async () => {
    vi.mocked(metadata.clearQueryHistory).mockResolvedValue({ deleted: 500, hasMore: true })
    page()
    await screen.findByText('业务库')
    await clearBatch()
    expect(await screen.findByText('已删除 500 条记录，仍有历史记录，可继续清理')).toBeInTheDocument()
    expect(screen.queryByText(/已清空/)).not.toBeInTheDocument()
    expect(metadata.clearQueryHistory).toHaveBeenCalledTimes(1)
  })

  it('reports completion only when the server confirms no remaining owned rows', async () => {
    vi.mocked(metadata.clearQueryHistory).mockResolvedValue({ deleted: 10, hasMore: false })
    page()
    await screen.findByText('业务库')
    await clearBatch()
    expect(await screen.findByText('已清空 10 条记录')).toBeInTheDocument()
  })

  it('ignores an older response after a newer refresh completes', async () => {
    let finishOld!: (items: QueryHistoryRecord[]) => void
    vi.mocked(metadata.listQueryHistory).mockReturnValueOnce(new Promise(resolve => { finishOld = resolve }))
      .mockResolvedValueOnce([{ ...own, sqlText: 'SELECT new_result' }])
    page()
    await waitFor(() => expect(metadata.listQueryHistory).toHaveBeenCalledTimes(1))
    const oldSignal = vi.mocked(metadata.listQueryHistory).mock.calls[0][1]
    fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
    await screen.findByText('SELECT new_result')
    await act(async () => finishOld([{ ...own, sqlText: 'SELECT stale_result' }]))
    expect(oldSignal?.aborted).toBe(true)
    expect(screen.queryByText('SELECT stale_result')).not.toBeInTheDocument()
    expect(screen.getByText('SELECT new_result')).toBeInTheDocument()
  })

  it('aborts the pending list request when the page unmounts', async () => {
    vi.mocked(metadata.listQueryHistory).mockReturnValue(new Promise(() => {}))
    const view = page()
    await waitFor(() => expect(metadata.listQueryHistory).toHaveBeenCalledTimes(1))
    const signal = vi.mocked(metadata.listQueryHistory).mock.calls[0][1]
    view.unmount()
    expect(signal?.aborted).toBe(true)
  })
})
