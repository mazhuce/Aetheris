import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as metadata from '@/api/metadata'
import type { DataSource, TestResult } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import DataSourcePage from './index'

vi.mock('@/api/metadata')

const source: DataSource = { id: 5, name: 'Spark 测试源', type: 'spark', host: 'relay', status: 'active' }

function renderPage() {
  return render(<AntdApp><DataSourcePage /></AntdApp>)
}

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(metadata.listDataSources).mockResolvedValue([{ ...source }])
})

describe('DataSourcePage connection health', () => {
  it('shows enabled configuration as neutral and unknown health without probing on open or refresh', async () => {
    renderPage()
    await screen.findByText(source.name)
    const table = within(screen.getByRole('region', { name: '数据源列表' }))
    expect(table.getByText('启用').closest('.status-badge')).toHaveClass('is-neutral')
    expect(table.getByText('未知')).toBeInTheDocument()
    expect(screen.getByText('配置已启用')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /刷新/ }))
    await waitFor(() => expect(metadata.listDataSources).toHaveBeenCalledTimes(2))
    expect(metadata.testDataSource).not.toHaveBeenCalled()
    expect(metadata.listDataSourceSchemas).not.toHaveBeenCalled()
    expect(metadata.listDataSourceTables).not.toHaveBeenCalled()
    expect(metadata.queryDataSource).not.toHaveBeenCalled()
    expect(metadata.syncMetadataTables).not.toHaveBeenCalled()
  })

  it('shows a pending manual probe and then the returned success timestamp and latency', async () => {
    let complete!: (result: TestResult) => void
    vi.mocked(metadata.testDataSource).mockReturnValue(new Promise((resolve) => { complete = resolve }))
    renderPage()
    const button = await screen.findByRole('button', { name: `测试数据源 ${source.name}` })
    fireEvent.click(button)
    expect(screen.getByText('探测中')).toBeInTheDocument()
    fireEvent.click(button)
    expect(metadata.testDataSource).toHaveBeenCalledTimes(1)
    const checkedAt = new Date().toISOString()
    await act(async () => complete({
      success: true,
      message: 'OK',
      latencyMs: 24,
      connectionHealth: { status: 'available', checkedAt, lastSuccessAt: checkedAt, expiresAt: new Date(Date.now() + 60_000).toISOString(), latencyMs: 24 }
    }))
    expect(screen.getByText('可用')).toBeInTheDocument()
    expect(screen.getByText('24 ms')).toBeInTheDocument()
    expect(screen.getByText(`最近成功：${formatDateTime(checkedAt)}`)).toBeInTheDocument()
  })

  it('shows safe dependency failure details while retaining historical success', async () => {
    const lastSuccessAt = '2026-09-06T08:00:00Z'
    vi.mocked(metadata.testDataSource).mockResolvedValue({
      success: false,
      message: 'legacy raw exception password=do-not-display',
      connectionHealth: {
        status: 'unavailable', checkedAt: new Date().toISOString(), lastSuccessAt,
        expiresAt: new Date(Date.now() + 60_000).toISOString(),
        failureCode: 'CONNECTION_REFUSED', failureMessage: '连接被拒绝，请检查 Spark Thrift Server 与转发服务。'
      }
    })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: `测试数据源 ${source.name}` }))
    await screen.findByText('不可用')
    const table = within(screen.getByRole('region', { name: '数据源列表' }))
    expect(table.getByText('连接被拒绝，请检查 Spark Thrift Server 与转发服务。')).toBeInTheDocument()
    expect(table.getByText(`最近成功：${formatDateTime(lastSuccessAt)}`)).toBeInTheDocument()
    expect(screen.queryByText(/do-not-display/)).not.toBeInTheDocument()
  })

  it('treats a lost probe response as unknown and hides raw HTTP errors', async () => {
    const checkedAt = new Date().toISOString()
    vi.mocked(metadata.listDataSources).mockResolvedValue([{
      ...source, connectionHealth: { status: 'available', checkedAt, lastSuccessAt: checkedAt, expiresAt: new Date(Date.now() + 60_000).toISOString() }
    }])
    vi.mocked(metadata.testDataSource).mockRejectedValue(new Error('jdbc://user:secret@host'))
    renderPage()
    await screen.findByText('可用')
    fireEvent.click(screen.getByRole('button', { name: `测试数据源 ${source.name}` }))
    await screen.findByText('未知')
    expect(screen.queryByText('可用')).not.toBeInTheDocument()
    expect(screen.getByText('未取得探测结果，请检查平台服务与网络后刷新或重试。')).toBeInTheDocument()
    expect(screen.queryByText(/user:secret/)).not.toBeInTheDocument()
  })

  it('does not display an expired successful result as available', async () => {
    vi.mocked(metadata.listDataSources).mockResolvedValue([{
      ...source, connectionHealth: { status: 'available', checkedAt: '2020-01-01T00:00:00Z', lastSuccessAt: '2020-01-01T00:00:00Z', expiresAt: '2020-01-01T00:01:00Z' }
    }])
    renderPage()
    await screen.findByText(source.name)
    expect(screen.getByText('未知')).toBeInTheDocument()
    expect(screen.queryByText('可用')).not.toBeInTheDocument()
    expect(screen.getByText('上次探测结果已过期或失效，请重新测试连接。')).toBeInTheDocument()
  })

  it('explains denied probe permission without reporting database failure or exposing server errors', async () => {
    vi.mocked(metadata.testDataSource).mockRejectedValue({ response: { status: 403, data: { message: 'private authorization details' } } })
    renderPage()
    fireEvent.click(await screen.findByRole('button', { name: `测试数据源 ${source.name}` }))
    await screen.findByText('没有测试此数据源连接的权限，请检查登录状态与数据源查询授权')
    expect(screen.getByText('未知')).toBeInTheDocument()
    expect(screen.queryByText('不可用')).not.toBeInTheDocument()
    expect(screen.queryByText(/private authorization details/)).not.toBeInTheDocument()
  })
})
