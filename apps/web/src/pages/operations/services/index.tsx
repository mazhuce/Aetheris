import { useCallback, useEffect, useState } from 'react'
import { App, Button, Row, Col, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, CloudServerOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { getServiceHealth, type ServiceStatus } from '@/api/system'
import { formatDateTime } from '@/utils/format'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'

/** 自动刷新间隔：健康探测本身是轻量调用，30 秒足够及时。 */
const REFRESH_INTERVAL_MS = 30_000

export default function ServiceMonitorPage() {
  const { message } = App.useApp()
  const [services, setServices] = useState<ServiceStatus[]>([])
  const [loading, setLoading] = useState(false)
  const pageSize = useResponsivePageSize()

  const refresh = useCallback(async (silent = false) => {
    setLoading(true)
    try {
      setServices(await getServiceHealth())
    } catch {
      if (!silent) message.error('服务健康状态获取失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
    const timer = setInterval(() => void refresh(true), REFRESH_INTERVAL_MS)
    return () => clearInterval(timer)
  }, [refresh])

  const upCount = services.filter((service) => service.status === 'UP').length
  const downCount = services.length - upCount

  const columns: TableColumnsType<ServiceStatus> = [
    {
      title: '服务',
      dataIndex: 'name',
      key: 'name',
      width: 200,
      render: (value: string) => (
        <Typography.Text strong style={{ fontSize: 13 }}>
          <CloudServerOutlined style={{ marginRight: 8, color: 'var(--text-tertiary)' }} />
          {value}
        </Typography.Text>
      )
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 120,
      render: (value: ServiceStatus['status']) =>
        value === 'UP' ? <Tag color="success">在线</Tag> : <Tag color="error">不可用</Tag>
    },
    {
      title: '探测详情',
      dataIndex: 'detail',
      key: 'detail',
      ellipsis: true,
      render: (value?: string) => (
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>{value || '—'}</Typography.Text>
      )
    },
    {
      title: '检查时间',
      dataIndex: 'checkedAt',
      key: 'checkedAt',
      width: 170,
      render: (value?: string) => formatDateTime(value)
    }
  ]

  return (
    <div className="service-monitor-page">
      <PageHeader
        description="平台各微服务的实时健康状态：网关、认证、元数据、质量、接入、治理、任务调度等核心组件，每 30 秒自动刷新。"
        extra={
          <Tooltip title={`每 ${REFRESH_INTERVAL_MS / 1000} 秒自动刷新`}>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>立即刷新</Button>
          </Tooltip>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><StatCard title="在线服务" value={String(upCount)} icon={<CheckCircleOutlined />} footer={`共 ${services.length} 个组件`} tone="green" /></Col>
        <Col xs={12} lg={6}><StatCard title="不可用" value={String(downCount)} icon={<CloseCircleOutlined />} footer={downCount > 0 ? '需要排查' : '一切正常'} tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="健康率" value={services.length ? `${Math.round((upCount / services.length) * 100)}%` : '—'} icon={<CheckCircleOutlined />} footer="在线服务占比" /></Col>
        <Col xs={12} lg={6}><StatCard title="探测间隔" value={`${REFRESH_INTERVAL_MS / 1000} s`} icon={<ReloadOutlined />} footer="页面自动轮询" /></Col>
      </Row>

      <DataSurface title="服务列表" description="全部核心组件" label="服务健康明细">
        <Table<ServiceStatus>
          rowKey="name"
          columns={columns}
          dataSource={services}
          loading={loading}
          pagination={{ pageSize, showSizeChanger: false, showTotal: (total) => `共 ${total} 个服务` }}
          locale={{ emptyText: <Typography.Text type="secondary">正在探测服务状态…</Typography.Text> }}
        />
      </DataSurface>

      <div style={{ marginTop: 16 }}>
        <Typography.Text type="secondary" style={{ fontSize: 12 }}>
          说明：HTTP 服务探测 /actuator/health 端点；数据库探测 TCP 端口。4xx 响应视为服务在线（路由或认证问题，非宕机）。
        </Typography.Text>
      </div>
    </div>
  )
}
