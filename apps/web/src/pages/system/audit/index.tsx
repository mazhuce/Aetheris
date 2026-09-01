import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Input, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, ClockCircleOutlined, ReloadOutlined, SafetyCertificateOutlined, UserOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import StatCard from '@/components/StatCard'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { listAuditLogs, type AuditLog } from '@/api/system'
import { formatDateTime } from '@/utils/format'

export default function AuditLogPage() {
  const { message } = App.useApp()
  const [logs, setLogs] = useState<AuditLog[]>([])
  const [loading, setLoading] = useState(false)
  const [username, setUsername] = useState('')
  const [method, setMethod] = useState<string>()
  const [success, setSuccess] = useState<boolean>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setLogs(await listAuditLogs({ username: username || undefined, method, success }))
    } catch {
      message.error('审计日志加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, method, success, username])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const metrics = useMemo(() => {
    const successful = logs.filter((log) => log.statusCode < 400).length
    const averageDuration = logs.length
      ? Math.round(logs.reduce((total, log) => total + log.durationMs, 0) / logs.length)
      : 0
    return {
      successful,
      averageDuration,
      users: new Set(logs.map((log) => log.username)).size
    }
  }, [logs])

  const columns: TableColumnsType<AuditLog> = [
    { title: '时间', dataIndex: 'createdAt', key: 'createdAt', width: 180, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作者',
      dataIndex: 'username',
      key: 'username',
      width: 140,
      render: (value: string, record) => (
        <Space size={6}><span>{value}</span><Tag>{record.userRole}</Tag></Space>
      )
    },
    { title: '方法', dataIndex: 'method', key: 'method', width: 90, render: (value: string) => <Tag>{value}</Tag> },
    { title: '请求路径', dataIndex: 'path', key: 'path', ellipsis: true, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
    {
      title: '结果',
      dataIndex: 'statusCode',
      key: 'statusCode',
      width: 100,
      render: (value: number) => <StatusBadge tone={value < 400 ? 'success' : 'danger'}>{value}</StatusBadge>
    },
    { title: '耗时', dataIndex: 'durationMs', key: 'durationMs', width: 100, render: (value: number) => `${value} ms` },
    { title: '客户端 IP', dataIndex: 'clientIp', key: 'clientIp', width: 130, render: (value?: string) => value || '—' }
  ]

  return (
    <div className="audit-page">
      <PageHeader
        description="记录平台写操作日志，追溯变更来源、执行结果与接口耗时。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新数据</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><StatCard title="审计事件" value={String(logs.length)} icon={<SafetyCertificateOutlined />} footer="最近 200 条记录" tone="neutral" /></Col>
        <Col xs={12} lg={6}><StatCard title="成功请求" value={String(metrics.successful)} icon={<CheckCircleOutlined />} footer="HTTP 状态码小于 400" tone="green" /></Col>
        <Col xs={12} lg={6}><StatCard title="平均耗时" value={`${metrics.averageDuration} ms`} icon={<ClockCircleOutlined />} footer="当前筛选范围" tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="活跃操作者" value={String(metrics.users)} icon={<UserOutlined />} footer="去重账号数量" tone="neutral" /></Col>
      </Row>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item"><span>操作者</span><Input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="输入用户名" allowClear className="filter-control-2xl" /></div>
          <div className="filter-item"><span>请求方法</span><Select value={method} onChange={setMethod} placeholder="全部" allowClear className="filter-control-sm" options={['POST', 'PUT', 'DELETE'].map((value) => ({ value, label: value }))} /></div>
          <div className="filter-item"><span>执行结果</span><Select value={success} onChange={setSuccess} placeholder="全部" allowClear className="filter-control-sm" options={[{ value: true, label: '成功' }, { value: false, label: '失败' }]} /></div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
        </div>
      </PageToolbar>

      <DataSurface title="操作明细" description="最近 200 条平台写操作" label="审计日志明细">
        <Table<AuditLog>
          rowKey="id"
          columns={columns}
          dataSource={logs}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无审计记录，平台写操作会自动出现在这里' }}
        />
      </DataSurface>
    </div>
  )
}
