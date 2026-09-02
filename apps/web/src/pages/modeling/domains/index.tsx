import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Empty, Row, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AppstoreOutlined, DatabaseOutlined, PartitionOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listTables, WAREHOUSE_LAYERS, type MetadataTable } from '@/api/metadata'

const OTHER_DOMAIN = '未归属域'
const layerColor: Record<string, string> = {
  ods: 'default', dwd: 'blue', dws: 'cyan', ads: 'purple', dim: 'gold', other: 'default'
}

export default function DomainPage() {
  const { message } = App.useApp()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTables(await listTables())
    } catch {
      message.error('主题域数据加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const domains = useMemo(() => {
    const byDomain = new Map<string, MetadataTable[]>()
    for (const table of tables) {
      const domain = table.domain || OTHER_DOMAIN
      if (!byDomain.has(domain)) byDomain.set(domain, [])
      byDomain.get(domain)!.push(table)
    }
    return Array.from(byDomain.entries())
      .map(([name, domainTables]) => ({
        name,
        tables: domainTables,
        layerCounts: WAREHOUSE_LAYERS
          .map((item) => ({
            layer: item.value,
            label: item.label,
            count: domainTables.filter((table) => (table.layer || 'other') === item.value).length
          }))
          .filter((item) => item.count > 0)
      }))
      .sort((a, b) => b.tables.length - a.tables.length)
  }, [tables])

  const columns: TableColumnsType<{ name: string; total: number; layers: string[] }> = [
    {
      title: '主题域',
      dataIndex: 'name',
      key: 'name',
      render: (value: string) => (
        <Typography.Text strong style={{ fontSize: 13 }}>
          <AppstoreOutlined style={{ marginRight: 8, color: 'var(--text-tertiary)' }} />
          {value}
        </Typography.Text>
      )
    },
    { title: '表数量', dataIndex: 'total', key: 'total', width: 100, sorter: (a, b) => a.total - b.total },
    {
      title: '分层分布',
      key: 'layers',
      render: (_, record) => (
        <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          {record.layers.length
            ? record.layers.map((item) => <Tag key={item}>{item}</Tag>)
            : <Typography.Text type="secondary">未分层</Typography.Text>}
        </div>
      )
    }
  ]

  const dataSource = domains.map((domain) => ({
    name: domain.name,
    total: domain.tables.length,
    layers: domain.layerCounts.map((item) => `${item.label} ${item.count}`)
  }))

  return (
    <div className="domain-page">
      <PageHeader
        description="数仓主题域视图：以业务域为中心统计各域的表规模与分层分布，作为数仓建模与域边界治理的依据。表的业务域在元数据同步时自动识别，也可在数据表页人工维护。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <StatCard title="主题域数量" value={String(domains.length)} icon={<AppstoreOutlined />} footer={`覆盖 ${tables.length} 张表`} />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="最大域"
            value={domains.length ? domains[0].name : '—'}
            icon={<PartitionOutlined />}
            footer={domains.length ? `${domains[0].tables.length} 张表` : '暂无数据'}
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="已分层表"
            value={String(tables.filter((table) => table.layer).length)}
            icon={<DatabaseOutlined />}
            footer={`占比 ${tables.length ? Math.round((tables.filter((table) => table.layer).length / tables.length) * 100) : 0}%`}
            tone="green"
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="未归属域的表"
            value={String(tables.filter((table) => !table.domain).length)}
            icon={<DatabaseOutlined />}
            footer="建议在元数据中补充"
            tone="orange"
          />
        </Col>
      </Row>

      <Row gutter={[16, 16]} style={{ marginTop: 16 }}>
        {domains.slice(0, 6).map((domain) => (
          <Col xs={24} sm={12} lg={8} key={domain.name}>
            <DataSurface
              title={domain.name}
              description={`${domain.tables.length} 张表`}
              label={`主题域 ${domain.name}`}
            >
              <div style={{ display: 'flex', gap: 6, flexWrap: 'wrap', padding: '4px 0' }}>
                {domain.layerCounts.map((item) => (
                  <Tag key={item.layer} color={layerColor[item.layer]}>
                    {item.label} · {item.count}
                  </Tag>
                ))}
                {!domain.layerCounts.length && <Typography.Text type="secondary">全部未分层</Typography.Text>}
              </div>
              <div style={{ marginTop: 8 }}>
                <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                  代表表：{domain.tables.slice(0, 3).map((table) => table.tableName).join('、') || '—'}
                </Typography.Text>
              </div>
            </DataSurface>
          </Col>
        ))}
      </Row>

      <DataSurface title="域清单" description="全部主题域汇总" label="主题域清单">
        {domains.length ? (
          <Table
            rowKey="name"
            size="middle"
            columns={columns}
            dataSource={dataSource}
            loading={loading}
            pagination={false}
          />
        ) : (
          <Empty description="暂无主题域数据，先同步数据源元数据" />
        )}
      </DataSurface>
    </div>
  )
}
