import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as metadata from '@/api/metadata'
import MetadataTablePage from '@/pages/metadata/tables'
import TableBrowserPage from '@/pages/assets/tables'
import AssetCatalogPage from './index'

vi.mock('@/api/metadata', async (original) => {
  const actual = await original<typeof import('@/api/metadata')>()
  return { ...actual, listTables: vi.fn(), listTablesPage: vi.fn(), listTableCatalog: vi.fn(), listBusinessDomains: vi.fn() }
})

beforeEach(() => {
  vi.clearAllMocks()
  vi.mocked(metadata.listTableCatalog).mockResolvedValue([])
  vi.mocked(metadata.listBusinessDomains).mockResolvedValue([])
})

describe('three main table pages use visible cursor navigation', () => {
  it.each([
    ['metadata', MetadataTablePage], ['table browser', TableBrowserPage], ['catalog', AssetCatalogPage],
  ] as const)('%s continues past an empty visibility budget page', async (_, Component) => {
    vi.mocked(metadata.listTablesPage).mockResolvedValueOnce({ items: [], nextCursor: 'continuation', hasMore: true })
      .mockResolvedValueOnce({ items: [{ id: 701, datasourceName: 'fixture', tableName: 'visible_after_denied_batch' }], nextCursor: null, hasMore: false })
    render(<MemoryRouter><App><Component /></App></MemoryRouter>)
    const next = screen.getByRole('button', { name: '下一页' })
    await waitFor(() => expect(next).toBeEnabled())
    fireEvent.click(next)
    await screen.findByText('visible_after_denied_batch')
    expect(vi.mocked(metadata.listTablesPage).mock.calls[1][1]).toMatchObject({ cursor: 'continuation', signal: expect.any(AbortSignal) })
    expect(metadata.listTables).not.toHaveBeenCalled()
    expect(next).toBeDisabled()
  })

  it('shows permission failure and never advertises a partial catalog as a complete count', async () => {
    vi.mocked(metadata.listTableCatalog).mockRejectedValue(new Error('compatibility scan limit'))
    vi.mocked(metadata.listTablesPage).mockRejectedValue({ response: { status: 503 } })
    render(<MemoryRouter><App><AssetCatalogPage /></App></MemoryRouter>)
    await screen.findByText('权限服务暂时不可用，请稍后重试')
    expect(screen.getByText('统计未完成')).toBeInTheDocument()
    expect(screen.getByText('完整计数暂不可用')).toBeInTheDocument()
    expect(screen.queryByText('共 0 个域')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: '下一页' })).toBeDisabled()
  })
})
