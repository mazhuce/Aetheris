import { useCallback, useEffect, useState } from 'react'
import { Alert, App, Button, Col, Input, Row, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, SearchOutlined, DotChartOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listBusinessDomains, listPublishedMetricsPage, listTables, type BusinessDomain, type MetadataTable, type MetricDefinition } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'
import { useVisibleListPage } from '@/hooks/useVisibleListPage'

/** 数据资产侧只展示经过发布流程的稳定指标；草稿与设计过程留在指标管理。 */
export default function MetricAssetsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const pageSize = useResponsivePageSize(8, 6, 5, 3)
  const listing = useVisibleListPage(
    (cursor, signal) => listPublishedMetricsPage(keyword || undefined, { cursor, size: pageSize, signal }),
    JSON.stringify([keyword, pageSize])
  )
  const refresh = useCallback(async () => {
    listing.refresh()
    setLoading(true)
    try {
      const [nextTables, nextDomains] = await Promise.all([listTables(), listBusinessDomains()])
      setTables(nextTables); setDomains(nextDomains)
    } catch { message.error('指标资产加载失败') } finally { setLoading(false) }
  }, [listing.refresh, message])
  useEffect(() => { void refresh() }, [refresh])
  const tableName = (id?: number) => tables.find((item) => item.id === id)?.tableName || '—'
  const columns: TableColumnsType<MetricDefinition> = [
    { title: '指标编码', dataIndex: 'metricCode', width: 160, render: (value) => <Typography.Text code>{value}</Typography.Text> },
    { title: '指标名称', dataIndex: 'metricName', width: 180, render: (value) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '业务域', dataIndex: 'businessDomain', width: 120, render: (value) => value || '—' },
    { title: '版本', dataIndex: 'version', width: 75, render: (value) => <Tag color="green">v{value}</Tag> },
    { title: '来源表', dataIndex: 'sourceTableId', width: 160, render: tableName },
    { title: '口径', width: 210, render: (_, row) => `${row.aggregation}${row.measureColumn ? `(${row.measureColumn})` : '(*)'}` },
    { title: '单位', dataIndex: 'unit', width: 70, render: (value) => value || '—' },
    { title: '负责人', dataIndex: 'owner', width: 100, render: (value) => value || '—' },
    { title: '发布时间', dataIndex: 'publishedAt', width: 170, render: formatDateTime },
    { title: '操作', width: 110, fixed: 'right', render: () => <Button type="link" size="small" onClick={() => navigate('/modeling/metrics')}>查看口径</Button> }
  ]
  return <>
    <PageHeader description="仅收录已通过真实字段校验并完成发布的企业指标，确保资产目录、看板和智能问数使用同一版本口径。" extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>} />
    <Row gutter={12} className="stat-row">
      <Col span={8}><StatCard title="本页已发布指标" value={String(listing.items.length)} icon={<DotChartOutlined />} /></Col>
      <Col span={8}><StatCard title="本页覆盖业务域" value={String(new Set(listing.items.map((item) => item.businessDomainId).filter(Boolean)).size)} icon={<DotChartOutlined />} tone="green" /></Col>
      <Col span={8}><StatCard title="本页稳定来源表" value={String(new Set(listing.items.map((item) => item.sourceTableId).filter(Boolean)).size)} icon={<DotChartOutlined />} tone="orange" /></Col>
    </Row>
    <Input allowClear prefix={<SearchOutlined />} value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索指标名称、编码或业务域" style={{ maxWidth: 380, margin: '16px 0' }} />
    <DataSurface title="指标资产目录" description={`${listing.hasMore ? '当前页仍有更多可消费指标' : '已到达结果末尾'}，${domains.filter((item) => item.status === 'active').length} 个启用业务域`} label="指标资产目录">
      {listing.error && <Alert type="error" showIcon message={listing.error} style={{ marginBottom: 12 }} />}
      <Table rowKey="id" size="small" loading={loading || listing.loading} columns={columns} dataSource={listing.items} scroll={{ x: 1300 }} pagination={false} />
      <Space style={{ marginTop: 12 }}>
        <Button disabled={!listing.canPrevious || listing.loading} onClick={listing.previous}>上一页</Button>
        <span>第 {listing.pageNumber} 页 · 本页 {listing.items.length} 条</span>
        <Button disabled={!listing.hasMore || listing.loading || !!listing.error} onClick={listing.next}>下一页</Button>
      </Space>
    </DataSurface>
  </>
}
