import { useEffect, useState } from 'react'
import { Alert, Card, Col, Row, Statistic, Table, Tag } from 'antd'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { getQueryAuditSummary, type QueryAuditSummary } from '@/api/metadata'

export default function QueryAuditPage() {
  const [summary, setSummary] = useState<QueryAuditSummary>()
  const [error, setError] = useState(false)
  useEffect(() => { void getQueryAuditSummary().then(setSummary).catch(() => setError(true)) }, [])
  return <div><PageHeader title="语义查询审计" description="查询使用量、失败率、P95 耗时与慢查询诊断" />
    {error && <Alert type="warning" showIcon message="暂无审计数据或当前用户没有查看权限" style={{ marginBottom: 16 }} />}
    <Row gutter={[16, 16]}>
      <Col xs={24} sm={12} lg={6}><Card><Statistic title="查询总数" value={summary?.total ?? 0} /></Card></Col>
      <Col xs={24} sm={12} lg={6}><Card><Statistic title="成功率" value={summary ? `${(summary.successRate * 100).toFixed(1)}%` : '—'} valueStyle={{ color: '#52c41a' }} /></Card></Col>
      <Col xs={24} sm={12} lg={6}><Card><Statistic title="P95 耗时" value={summary?.p95DurationMs ?? 0} suffix="ms" /></Card></Col>
      <Col xs={24} sm={12} lg={6}><Card><Statistic title="慢查询" value={summary?.slowQueries ?? 0} valueStyle={{ color: '#fa541c' }} /></Card></Col>
    </Row>
    <DataSurface style={{ marginTop: 16 }}><Table pagination={false} dataSource={summary ? [{ key: 'all', metric: '全部查询', total: summary.total, success: summary.success, failed: summary.failed, p50: `${Math.round(summary.p50DurationMs)} ms`, p95: `${Math.round(summary.p95DurationMs)} ms` }] : []} columns={[{ title: '范围', dataIndex: 'metric' }, { title: '总数', dataIndex: 'total' }, { title: '成功', dataIndex: 'success' }, { title: '失败', dataIndex: 'failed', render: (v: number) => <Tag color={v ? 'red' : 'green'}>{v}</Tag> }, { title: 'P50', dataIndex: 'p50' }, { title: 'P95', dataIndex: 'p95' }]} /></DataSurface>
  </div>
}
