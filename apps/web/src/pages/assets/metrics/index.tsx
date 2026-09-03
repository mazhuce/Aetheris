import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Input, Row, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, SearchOutlined, DotChartOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listBusinessDomains, listPublishedMetrics, listTables, type BusinessDomain, type MetadataTable, type MetricDefinition } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

/** 数据资产侧只展示经过发布流程的稳定指标；草稿与设计过程留在指标管理。 */
export default function MetricAssetsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [metrics, setMetrics] = useState<MetricDefinition[]>([])
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [nextMetrics, nextTables, nextDomains] = await Promise.all([listPublishedMetrics(), listTables(), listBusinessDomains()])
      setMetrics(nextMetrics); setTables(nextTables); setDomains(nextDomains)
    } catch { message.error('指标资产加载失败') } finally { setLoading(false) }
  }, [message])
  useEffect(() => { void refresh() }, [refresh])
  const filtered = useMemo(() => {
    const key = keyword.trim().toLowerCase()
    return key ? metrics.filter((item) => item.metricCode.toLowerCase().includes(key) || item.metricName.toLowerCase().includes(key) || (item.businessDomain || '').toLowerCase().includes(key)) : metrics
  }, [keyword, metrics])
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
      <Col span={8}><StatCard title="已发布指标" value={String(metrics.length)} icon={<DotChartOutlined />} /></Col>
      <Col span={8}><StatCard title="覆盖业务域" value={String(new Set(metrics.map((item) => item.businessDomainId).filter(Boolean)).size)} icon={<DotChartOutlined />} tone="green" /></Col>
      <Col span={8}><StatCard title="稳定来源表" value={String(new Set(metrics.map((item) => item.sourceTableId).filter(Boolean)).size)} icon={<DotChartOutlined />} tone="orange" /></Col>
    </Row>
    <Input allowClear prefix={<SearchOutlined />} value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索指标名称、编码或业务域" style={{ maxWidth: 380, margin: '16px 0' }} />
    <DataSurface title="指标资产目录" description={`共 ${filtered.length} 个可消费指标，${domains.filter((item) => item.status === 'active').length} 个启用业务域`} label="指标资产目录">
      <Table rowKey="id" size="small" loading={loading} columns={columns} dataSource={filtered} scroll={{ x: 1300 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个指标资产` }} />
    </DataSurface>
  </>
}
