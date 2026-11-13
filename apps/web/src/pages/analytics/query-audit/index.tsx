import { useEffect, useState } from 'react'
import { Alert, Col, Row, Table, Tag } from 'antd'
import { BarChartOutlined, CheckCircleOutlined, ClockCircleOutlined, WarningOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { getQueryAuditSummary, type QueryAuditSummary } from '@/api/metadata'

export default function QueryAuditPage() {
  const [summary, setSummary] = useState<QueryAuditSummary>()
  const [error, setError] = useState(false)
  useEffect(() => { void getQueryAuditSummary().then(setSummary).catch(() => setError(true)) }, [])
  return <>
    <PageHeader title="语义查询审计" description="查询使用量、失败率、P95 耗时与慢查询诊断" />
    {error && <Alert type="warning" showIcon message="暂无审计数据或当前用户没有查看权限" />}
    <Row gutter={12} className="stat-row">
      <Col xs={12} lg={6}><StatCard title="查询总数" value={String(summary?.total ?? 0)} icon={<BarChartOutlined />} /></Col>
      <Col xs={12} lg={6}><StatCard title="成功率" value={summary ? `${(summary.successRate * 100).toFixed(1)}%` : '—'} icon={<CheckCircleOutlined />} tone="green" /></Col>
      <Col xs={12} lg={6}><StatCard title="P95 耗时" value={summary ? `${summary.p95DurationMs} ms` : '—'} icon={<ClockCircleOutlined />} /></Col>
      <Col xs={12} lg={6}><StatCard title="慢查询" value={String(summary?.slowQueries ?? 0)} icon={<WarningOutlined />} tone="orange" /></Col>
    </Row>
    <DataSurface title="查询分布" description="按范围汇总的成功、失败与耗时分位"><Table pagination={false} dataSource={summary ? [{ key: 'all', metric: '全部查询', total: summary.total, success: summary.success, failed: summary.failed, p50: `${Math.round(summary.p50DurationMs)} ms`, p95: `${Math.round(summary.p95DurationMs)} ms` }] : []} columns={[{ title: '范围', dataIndex: 'metric' }, { title: '总数', dataIndex: 'total' }, { title: '成功', dataIndex: 'success' }, { title: '失败', dataIndex: 'failed', render: (v: number) => <Tag color={v ? 'error' : 'success'}>{v}</Tag> }, { title: 'P50', dataIndex: 'p50' }, { title: 'P95', dataIndex: 'p95' }]} /></DataSurface>
  </>
}
