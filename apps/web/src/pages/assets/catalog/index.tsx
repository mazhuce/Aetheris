import { useCallback, useEffect, useMemo, useState } from 'react'
import { Alert, Button, Empty, Input, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { FolderOpenOutlined, ReloadOutlined, SearchOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { listTablesPage, listTableCatalog, WAREHOUSE_LAYERS, warehouseLayerLabel, type MetadataTable, type TableCatalogGroup } from '@/api/metadata'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'
import { useVisibleListPage } from '@/hooks/useVisibleListPage'

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
  const navigate = useNavigate()
  const [groups, setGroups] = useState<TableCatalogGroup[]>([])
  const [catalogLoading, setCatalogLoading] = useState(true)
  const [catalogError, setCatalogError] = useState(false)
  const [catalogRevision, setCatalogRevision] = useState(0)
  const [keyword, setKeyword] = useState('')
  const [selectedDomain, setSelectedDomain] = useState<string>()
  const pageSize = useResponsivePageSize(8, 6, 5, 3)
  const selected = selectedDomain === undefined ? undefined : JSON.parse(selectedDomain) as string | null
  const listing = useVisibleListPage((cursor, signal) => listTablesPage(
    { keyword, domain: selected ?? undefined, unassigned: selected === null || undefined }, { cursor, size: pageSize, signal }),
    JSON.stringify([keyword, selectedDomain, pageSize]))

  const refresh = useCallback(() => { listing.refresh(); setCatalogRevision((value) => value + 1) }, [listing.refresh])

  useEffect(() => {
    const controller = new AbortController()
    setCatalogLoading(true); setCatalogError(false); setGroups([])
    listTableCatalog(keyword, { signal: controller.signal }).then((values) => {
      if (!controller.signal.aborted) setGroups(values)
    }).catch(() => { if (!controller.signal.aborted) setCatalogError(true) })
      .finally(() => { if (!controller.signal.aborted) setCatalogLoading(false) })
    return () => controller.abort()
  }, [keyword, catalogRevision])

  /** 域 → 层 → 表 的两级目录结构。 */
  const catalog = useMemo(() => {
    const byDomain = new Map<string, { domain: string | null; layers: Map<string, number> }>()
    for (const group of groups) {
      const key = JSON.stringify(group.domain)
      const layer = group.layer || 'other'
      const value = byDomain.get(key) ?? { domain: group.domain, layers: new Map<string, number>() }
      value.layers.set(layer, (value.layers.get(layer) ?? 0) + group.count)
      byDomain.set(key, value)
    }
    // 域内层数多、表多的排前面，主要资产优先呈现
    return Array.from(byDomain.entries())
      .map(([key, { domain, layers }]) => ({
        key, domain: domain || OTHER_DOMAIN,
        layerEntries: WAREHOUSE_LAYERS
          .filter((item) => layers.has(item.value))
          .map((item) => ({ layer: item.value, label: item.label, count: layers.get(item.value)! })),
        total: Array.from(layers.values()).reduce((sum, count) => sum + count, 0)
      }))
      .sort((a, b) => b.total - a.total)
  }, [groups])

  const activeDomain = catalog.find((entry) => entry.key === selectedDomain)

  const columns: TableColumnsType<MetadataTable> = [
    {
      title: '表名',
      dataIndex: 'tableName',
      key: 'tableName',
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
          onChange={(event) => { setKeyword(event.target.value); setSelectedDomain(undefined) }}
          style={{ maxWidth: 360 }}
        />
      </div>

      <div style={{ display: 'grid', gridTemplateColumns: 'minmax(220px, 280px) minmax(0, 1fr)', gap: 16, alignItems: 'start' }}>
        <DataSurface title="主题域" description={catalogLoading ? '目录统计加载中' : catalogError ? '统计未完成' : `共 ${catalog.length} 个域`} label="域目录">
          {catalogError && <Alert type="error" message="目录统计暂时无法完整加载，请重试" />}
          <div style={{ display: 'flex', flexDirection: 'column', gap: 4, padding: '8px 4px' }}>
            {catalog.map((entry) => {
              const active = selectedDomain === entry.key
              return (
                <button
                  key={entry.key}
                  onClick={() => setSelectedDomain(active ? undefined : entry.key)}
                  style={{
                    textAlign: 'left',
                    padding: '10px 12px',
                    borderRadius: 'var(--radius-lg)',
                    border: `1px solid ${active ? 'var(--accent-border)' : 'transparent'}`,
                    background: active ? 'var(--accent-subtle)' : 'transparent',
                    cursor: 'pointer',
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    width: '100%'
                  }}
                >
                  <span style={{ fontSize: 'var(--font-body)', fontWeight: active ? 600 : 400 }}>
                    <FolderOpenOutlined style={{ marginRight: 8, color: 'var(--text-tertiary)' }} />
                    {entry.domain}
                  </span>
                  <Typography.Text type="secondary" style={{ fontSize: 'var(--font-table)' }}>{entry.total} 表</Typography.Text>
                </button>
              )
            })}
            {!catalog.length && !catalogLoading && !catalogError && (
              <Empty description="暂无资产" />
            )}
          </div>
        </DataSurface>

        <DataSurface
          title={activeDomain ? `${activeDomain.domain} · 资产明细` : '全部资产'}
          description={catalogLoading ? '目录统计加载中' : catalogError ? '完整计数暂不可用' : activeDomain
            ? `${activeDomain.total} 张表 · ${activeDomain.layerEntries.length} 个层级`
            : `共 ${catalog.reduce((sum, entry) => sum + entry.total, 0)} 张表，点击左侧域查看分层分布`}
          label="资产明细"
        >
          {activeDomain && (
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
              {activeDomain.layerEntries.map((entry) => (
                <Tag key={entry.layer} color={layerColor[entry.layer]}>
                  {entry.label} · {entry.count} 表
                </Tag>
              ))}
            </div>
          )}
          {listing.error && <Alert type="error" message={listing.error} />}
          <Table<MetadataTable>
            rowKey="id"
            size="middle"
            columns={columns}
            dataSource={listing.items}
            loading={listing.loading}
            pagination={false}
            locale={{ emptyText: <Typography.Text type="secondary">{listing.hasMore ? '暂未找到可见记录，可继续查看' : '暂无资产。先在元数据中心同步数据源，或在数据表页手工登记。'}</Typography.Text> }}
          />
          <Space style={{ marginTop: 12 }}>
            <Button disabled={!listing.canPrevious || listing.loading} onClick={listing.previous}>上一页</Button>
            <span>第 {listing.pageNumber} 页 · 本页 {listing.items.length} 条</span>
            <Button disabled={!listing.hasMore || listing.loading || !!listing.error} onClick={listing.next}>下一页</Button>
          </Space>
        </DataSurface>
      </div>
    </div>
  )
}
