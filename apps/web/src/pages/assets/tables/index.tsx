import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Drawer, Input, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { DatabaseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import {
  listTablesPage,
  listTableCatalog,
  type MetadataTable,
  type DataSourceColumn,
  type TableCatalogGroup
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'
import { useVisibleListPage } from '@/hooks/useVisibleListPage'
import { useTableDetail } from './useTableDetail'

/** 数仓层级元数据：与 WarehouseLayerResolver 的约定一致。 */
const layerMeta: Record<string, { label: string; color: string }> = {
  ods: { label: 'ODS 贴源层', color: 'default' },
  dwd: { label: 'DWD 明细层', color: 'blue' },
  dws: { label: 'DWS 汇总层', color: 'cyan' },
  ads: { label: 'ADS 应用层', color: 'purple' },
  dim: { label: 'DIM 维度层', color: 'gold' }
}

export default function TableBrowserPage() {
  const [keyword, setKeyword] = useState('')
  const [layer, setLayer] = useState<string>()
  const [domain, setDomain] = useState<string | null>()
  const [catalog, setCatalog] = useState<TableCatalogGroup[]>([])
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [catalogError, setCatalogError] = useState(false)
  const [catalogRevision, setCatalogRevision] = useState(0)
  // 抽屉：表详情（字段结构 + 数据预览）
  const { detail, open: openDetail, close: closeDetail, retry: retryDetail } = useTableDetail()
  const active = detail?.table
  const columns = detail?.columns.data ?? []
  const preview = detail?.preview.data
  const pageSize = useResponsivePageSize()
  const domainValue = domain === undefined ? undefined : JSON.stringify(domain)
  const listing = useVisibleListPage((cursor, signal) => listTablesPage(
    { keyword, layer, domain: domain ?? undefined, unassigned: domain === null || undefined }, { cursor, size: pageSize, signal }),
    JSON.stringify([keyword, layer, domainValue, pageSize]))

  const { refresh: refreshListing } = listing
  const refresh = useCallback(() => { refreshListing(); setCatalogRevision((value) => value + 1) }, [refreshListing])

  useEffect(() => {
    const controller = new AbortController()
    setCatalogLoading(true); setCatalogError(false)
    listTableCatalog(undefined, { signal: controller.signal }).then((groups) => {
      if (!controller.signal.aborted) setCatalog(groups)
    }).catch(() => { if (!controller.signal.aborted) setCatalogError(true) })
      .finally(() => { if (!controller.signal.aborted) setCatalogLoading(false) })
    return () => controller.abort()
  }, [catalogRevision])

  const domains = useMemo(
    () => Array.from(new Set(catalog.map((group) => group.domain))),
    [catalog]
  )

  const columnsDef: TableColumnsType<MetadataTable> = [
    {
      title: '表名',
      dataIndex: 'tableName',
      key: 'tableName',
      width: 260,
      render: (value: string, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 'var(--font-body)' }}>{value}</Typography.Text>
          {record.tableComment && (
            <div style={{ fontSize: 'var(--font-table)', color: 'var(--text-tertiary)' }}>{record.tableComment}</div>
          )}
        </div>
      )
    },
    {
      title: '层级',
      dataIndex: 'layer',
      key: 'layer',
      width: 110,
      render: (value?: string) => {
        if (!value) return '—'
        const meta = layerMeta[value.toLowerCase()]
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : <Tag>{value}</Tag>
      }
    },
    {
      title: '业务域',
      dataIndex: 'domain',
      key: 'domain',
      width: 100,
      render: (value?: string) => value || '—'
    },
    {
      title: '数据源',
      dataIndex: 'datasourceName',
      key: 'datasourceName',
      width: 130,
      render: (value: string) => <Typography.Text type="secondary">{value}</Typography.Text>
    },
    { title: 'Schema', dataIndex: 'schemaName', key: 'schemaName', width: 100, render: (value?: string) => value || '—' },
    {
      title: '字段数',
      dataIndex: 'columnCount',
      key: 'columnCount',
      width: 80,
      render: (value?: number) => (value === undefined || value === null ? '—' : value)
    },
    {
      title: '行数',
      dataIndex: 'rowCount',
      key: 'rowCount',
      width: 110,
      render: (value?: number) => (value === undefined || value === null ? '—' : value.toLocaleString())
    },
    {
      title: '同步时间',
      dataIndex: 'syncedAt',
      key: 'syncedAt',
      width: 160,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '操作',
      key: 'actions',
      width: 90,
      render: (_, record) => (
        <Button size="small" type="link" onClick={() => void openDetail(record)}>查看详情</Button>
      )
    }
  ]

  return (
    <div className="table-browser-page">
      <PageHeader
        description="浏览平台纳管的全部数据表：按数仓层级、业务域筛选，查看字段结构与样例数据。表元数据来自数据源同步或手工登记。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>搜索</span>
            <Input
              allowClear
              prefix={<SearchOutlined />}
              placeholder="表名 / 注释 / 业务域"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              className="filter-control-sm"
              style={{ width: 200 }}
            />
          </div>
          <div className="filter-item">
            <span>层级</span>
            <Select
              placeholder="全部层级"
              allowClear
              value={layer}
              onChange={setLayer}
              className="filter-control-sm"
              options={Object.entries(layerMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <div className="filter-item">
            <span>业务域</span>
            <Select
              placeholder="全部域"
              allowClear
              value={domainValue}
              onChange={(value: string | undefined) => setDomain(value === undefined ? undefined : JSON.parse(value) as string | null)}
              className="filter-control-sm"
              loading={catalogLoading}
              disabled={catalogError}
              options={domains.map((value) => ({ value: JSON.stringify(value), label: value || '未归属域' }))}
            />
          </div>
        </div>
      </PageToolbar>

      <DataSurface
        title="数据表"
        description={`本页 ${listing.items.length} 张${!catalogLoading && !catalogError ? `（可见总数 ${catalog.reduce((sum, group) => sum + group.count, 0)}）` : ''}`}
        label="数据表明细"
      >
        {(listing.error || catalogError) && <Alert type="error" message={listing.error || '业务域筛选暂时无法完整加载，请重试'} />}
        <Table<MetadataTable>
          rowKey="id"
          columns={columnsDef}
          dataSource={listing.items}
          loading={listing.loading}
          scroll={{ x: 1200 }}
          pagination={false}
          locale={{ emptyText: <Typography.Text type="secondary"><DatabaseOutlined /> {listing.hasMore ? '暂未找到可见记录，可继续查看' : '暂无表元数据。去元数据中心对数据源执行同步，或手工登记表。'}</Typography.Text> }}
        />
        <Space style={{ marginTop: 12 }}>
          <Button disabled={!listing.canPrevious || listing.loading} onClick={listing.previous}>上一页</Button>
          <span>第 {listing.pageNumber} 页 · 本页 {listing.items.length} 条</span>
          <Button disabled={!listing.hasMore || listing.loading || !!listing.error} onClick={listing.next}>下一页</Button>
        </Space>
      </DataSurface>

      <Drawer
        title={active ? `${active.tableName} · 表详情` : '表详情'}
        placement="right"
        width={680}
        open={active !== undefined}
        onClose={closeDetail}
      >
        {active && (
          <div>
            <Typography.Text type="secondary">
              {active.tableComment || '（无表注释）'} · 数据源 {active.datasourceName}
              {active.schemaName ? ` / ${active.schemaName}` : ''}
            </Typography.Text>
            {!active.datasourceId ? <Alert type="info" message="未关联数据源，无法加载详情" /> : <Tabs
              key={detail?.requestId}
              className="table-detail-tabs"
              items={[
                {
                  key: 'columns',
                  label: `字段结构${columns.length ? `（${columns.length}）` : ''}`,
                  children: (
                    <DataSurface label="字段列表">
                      {detail?.columns.error ? <Alert
                        type="error"
                        message={detail.columns.error}
                        action={<Button size="small" onClick={() => retryDetail('columns')}>重试字段结构</Button>}
                      /> :
                      <Table<DataSourceColumn>
                        rowKey={(record) => record.name}
                        size="small"
                        pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 个字段` }}
                        loading={detail?.columns.loading}
                        dataSource={columns}
                        locale={{ emptyText: detail?.columns.loading ? '加载中…' : '暂无字段' }}
                        columns={[
                          { title: '字段名', dataIndex: 'name', key: 'name' },
                          { title: '类型', dataIndex: 'type', key: 'type', width: 130 },
                          {
                            title: '说明',
                            dataIndex: 'remarks',
                            key: 'remarks',
                            ellipsis: true,
                            render: (value?: string) => value || '—'
                          }
                        ]}
                      />}
                    </DataSurface>
                  )
                },
                {
                  key: 'preview',
                  label: `数据预览${preview ? `（${preview.rows.length}）` : ''}`,
                  children: detail?.preview.error ? <Alert
                    type="error"
                    message={detail.preview.error}
                    action={<Button size="small" onClick={() => retryDetail('preview')}>重试数据预览</Button>}
                  /> : preview ? (
                    <DataSurface description="前 20 行样例" label="样例数据">
                      <Table
                        rowKey={(_, index) => String(index)}
                        size="small"
                        pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 行` }}
                        scroll={{ x: true }}
                        dataSource={preview.rows.map((row) => ({ key: JSON.stringify(row), row }))}
                        locale={{ emptyText: '暂无样例数据' }}
                        columns={preview.columns.map((column) => ({
                          title: column,
                          key: column,
                          render: (_: unknown, record: { row: unknown[] }) => {
                            const index = preview.columns.indexOf(column)
                            const cell = record.row[index]
                            return cell === null || cell === undefined ? 'NULL' : String(cell)
                          }
                        }))}
                      />
                    </DataSurface>
                  ) : (
                    <Typography.Text type="secondary">{detail?.preview.loading ? '加载中…' : '预览不可用'}</Typography.Text>
                  )
                }
              ]}
            />}
          </div>
        )}
      </Drawer>
    </div>
  )
}
