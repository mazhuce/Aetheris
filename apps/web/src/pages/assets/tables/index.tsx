import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Drawer, Input, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { DatabaseOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import {
  listTables,
  previewDataSourceTable,
  listDataSourceColumns,
  type MetadataTable,
  type DataSourceColumn
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

/** 数仓层级元数据：与 WarehouseLayerResolver 的约定一致。 */
const layerMeta: Record<string, { label: string; color: string }> = {
  ods: { label: 'ODS 贴源层', color: 'default' },
  dwd: { label: 'DWD 明细层', color: 'blue' },
  dws: { label: 'DWS 汇总层', color: 'cyan' },
  ads: { label: 'ADS 应用层', color: 'purple' },
  dim: { label: 'DIM 维度层', color: 'gold' }
}

interface PreviewRow {
  columns: string[]
  rows: unknown[][]
}

export default function TableBrowserPage() {
  const { message } = App.useApp()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [layer, setLayer] = useState<string>()
  const [domain, setDomain] = useState<string>()
  // 抽屉：表详情（字段结构 + 数据预览）
  const [active, setActive] = useState<MetadataTable>()
  const [columns, setColumns] = useState<DataSourceColumn[]>([])
  const [preview, setPreview] = useState<PreviewRow>()
  const [detailLoading, setDetailLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTables(await listTables())
    } catch {
      message.error('表元数据加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const domains = useMemo(
    () => Array.from(new Set(tables.map((table) => table.domain).filter(Boolean))) as string[],
    [tables]
  )

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    return tables.filter((table) => {
      if (layer && table.layer !== layer) return false
      if (domain && table.domain !== domain) return false
      if (!kw) return true
      // 与后端 search 一致：表名、注释、业务域都参与匹配
      return (
        table.tableName.toLowerCase().includes(kw)
        || (table.tableComment ?? '').toLowerCase().includes(kw)
        || (table.domain ?? '').toLowerCase().includes(kw)
      )
    })
  }, [tables, keyword, layer, domain])

  const openDetail = async (table: MetadataTable) => {
    setActive(table)
    setColumns([])
    setPreview(undefined)
    if (!table.datasourceId) return
    setDetailLoading(true)
    try {
      const schema = table.schemaName ?? 'public'
      const [columnList, previewData] = await Promise.all([
        listDataSourceColumns(table.datasourceId, schema, table.tableName).catch(() => []),
        previewDataSourceTable(table.datasourceId, schema, table.tableName).catch(() => undefined)
      ])
      setColumns(columnList)
      setPreview(previewData)
    } catch {
      message.warning('字段结构与数据预览不可用（数据源可能已断开）')
    } finally {
      setDetailLoading(false)
    }
  }

  const columnsDef: TableColumnsType<MetadataTable> = [
    {
      title: '表名',
      dataIndex: 'tableName',
      key: 'tableName',
      width: 260,
      render: (value: string, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 13 }}>{value}</Typography.Text>
          {record.tableComment && (
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{record.tableComment}</div>
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
              value={domain}
              onChange={setDomain}
              className="filter-control-sm"
              options={domains.map((value) => ({ value, label: value }))}
            />
          </div>
        </div>
      </PageToolbar>

      <DataSurface
        title="数据表"
        description={`共 ${filtered.length} 张（纳管总数 ${tables.length}）`}
        label="数据表明细"
      >
        <Table<MetadataTable>
          rowKey="id"
          columns={columnsDef}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 15, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary"><DatabaseOutlined /> 暂无表元数据。去元数据中心对数据源执行同步，或手工登记表。</Typography.Text> }}
        />
      </DataSurface>

      <Drawer
        title={active ? `${active.tableName} · 表详情` : '表详情'}
        placement="right"
        width={680}
        open={active !== undefined}
        onClose={() => setActive(undefined)}
      >
        {active && (
          <div style={{ display: 'grid', gap: 16 }}>
            <Typography.Text type="secondary">
              {active.tableComment || '（无表注释）'} · 数据源 {active.datasourceName}
              {active.schemaName ? ` / ${active.schemaName}` : ''}
            </Typography.Text>

            <DataSurface title="字段结构" label="字段列表">
              <Table<DataSourceColumn>
                rowKey={(record) => record.name}
                size="small"
                pagination={false}
                loading={detailLoading}
                dataSource={columns}
                locale={{ emptyText: '字段信息不可用或数据源未同步' }}
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
              />
            </DataSurface>

            <DataSurface title="数据预览" description="前 20 行样例" label="样例数据">
              {preview ? (
                <Table
                  rowKey={(_, index) => String(index)}
                  size="small"
                  pagination={false}
                  scroll={{ x: true }}
                  dataSource={preview.rows.map((row) => ({ key: JSON.stringify(row), row }))}
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
              ) : (
                <Typography.Text type="secondary">{detailLoading ? '加载中…' : '预览不可用'}</Typography.Text>
              )}
            </DataSurface>
          </div>
        )}
      </Drawer>
    </div>
  )
}
