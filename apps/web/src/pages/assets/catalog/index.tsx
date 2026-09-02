import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Empty, Input, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { FolderOpenOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { listTables, WAREHOUSE_LAYERS, warehouseLayerLabel, type MetadataTable } from '@/api/metadata'

const layerColor: Record<string, string> = {
  ods: 'default',
  dwd: 'blue',
  dws: 'cyan',
  ads: 'purple',
  dim: 'gold',
  other: 'default'
}

const OTHER_DOMAIN = '未归属域'

export default function AssetCatalogPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [selectedDomain, setSelectedDomain] = useState<string>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTables(await listTables())
    } catch {
      message.error('资产目录加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 域 → 层 → 表 的两级目录结构。 */
  const catalog = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    const matched = tables.filter((table) => {
      if (!kw) return true
      return (
        table.tableName.toLowerCase().includes(kw)
        || (table.tableComment ?? '').toLowerCase().includes(kw)
      )
    })
    const byDomain = new Map<string, Map<string, MetadataTable[]>>()
    for (const table of matched) {
      const domain = table.domain || OTHER_DOMAIN
      const layer = table.layer || 'other'
      if (!byDomain.has(domain)) byDomain.set(domain, new Map())
      const layers = byDomain.get(domain)!
      if (!layers.has(layer)) layers.set(layer, [])
      layers.get(layer)!.push(table)
    }
    // 域内层数多、表多的排前面，主要资产优先呈现
    return Array.from(byDomain.entries())
      .map(([domain, layers]) => ({
        domain,
        layerEntries: WAREHOUSE_LAYERS
          .filter((item) => layers.has(item.value))
          .map((item) => ({ layer: item.value, label: item.label, tables: layers.get(item.value)! })),
        total: Array.from(layers.values()).reduce((sum, list) => sum + list.length, 0)
      }))
      .sort((a, b) => b.total - a.total)
  }, [tables, keyword])

  const activeDomain = catalog.find((entry) => entry.domain === selectedDomain)
  const visibleTables = activeDomain
    ? activeDomain.layerEntries.flatMap((entry) => entry.tables)
    : []

  const columns: TableColumnsType<MetadataTable> = [
    {
      title: '表名',
      dataIndex: 'tableName',
      key: 'tableName',
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
      width: 100,
      render: (value?: string) => <Tag color={layerColor[value ?? 'other']}>{warehouseLayerLabel(value)}</Tag>
    },
    { title: '数据源', dataIndex: 'datasourceName', key: 'datasourceName', width: 130, render: (v: string) => <Typography.Text type="secondary">{v}</Typography.Text> },
    { title: '字段数', dataIndex: 'columnCount', key: 'columnCount', width: 80, render: (v?: number) => v ?? '—' },
    {
      title: '行数',
      dataIndex: 'rowCount',
      key: 'rowCount',
      width: 110,
      render: (v?: number) => (v === undefined || v === null ? '—' : v.toLocaleString())
    },
    {
      title: '操作',
      key: 'actions',
      width: 110,
      render: () => (
        <Button size="small" type="link" onClick={() => navigate('/assets/tables')}>表详情</Button>
      )
    }
  ]

  return (
    <div className="asset-catalog-page">
      <PageHeader
        description="企业数据资产全景目录：按业务主题域 → 数仓层级两级组织的资产地图，快速了解「有哪些域、每个域有多少表、分布在哪一层」。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <div style={{ marginBottom: 16 }}>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索表名或注释，定位资产"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          style={{ maxWidth: 360 }}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 280px) minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
        <DataSurface title="主题域" description={`共 ${catalog.length} 个域`} label="域目录">
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '8px 4px' }}>
            {catalog.map((entry) => {
              const active = selectedDomain === entry.domain
              return (
                <button
                  key={entry.domain}
                  onClick={() => setSelectedDomain(active ? undefined : entry.domain)}
                  style={{
                    textAlign: 'left',
                    padding: '10px 12px',
                    borderRadius: 8,
                    border: `1px solid ${active ? 'var(--brand-primary, #1677ff)' : 'transparent'}`,
                    background: active ? 'rgba(22, 119, 255, 0.08)' : 'transparent',
                    cursor: 'pointer',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    width: '100%'
                  }}
                >
                  <span style={{ fontSize: 13, fontWeight: active ? 600 : 400 }}>
                    <FolderOpenOutlined style={{ marginRight: 8, color: 'var(--text-tertiary)' }} />
                    {entry.domain}
                  </span>
                  <Typography.Text type="secondary" style={{ fontSize: 12 }}>{entry.total} 表</Typography.Text>
                </button>
              )
            })}
            {!catalog.length && !loading && (
              <Empty description="暂无资产" />
            )}
          </div>
        </DataSurface>

        <DataSurface
          title={activeDomain ? `${activeDomain.domain} · 资产明细` : '全部资产'}
          description={activeDomain
            ? `${activeDomain.total} 张表 · ${activeDomain.layerEntries.length} 个层级`
            : `共 ${tables.length} 张表，点击左侧域查看分层分布`}
          label="资产明细"
        >
          {activeDomain && (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
              {activeDomain.layerEntries.map((entry) => (
                <Tag key={entry.layer} color={layerColor[entry.layer]}>
                  {entry.label} · {entry.tables.length} 表
                </Tag>
              ))}
            </div>
          )}
          <Table<MetadataTable>
            rowKey="id"
            size="middle"
            columns={columns}
            dataSource={activeDomain ? visibleTables : catalog.flatMap((entry) => entry.layerEntries.flatMap((layer) => layer.tables))}
            loading={loading}
            pagination={{ pageSize: 15, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
            locale={{ emptyText: <Typography.Text type="secondary">暂无资产。先在元数据中心同步数据源，或在数据表页手工登记。</Typography.Text> }}
          />
        </DataSurface>
      </div>
    </div>
  )
}
