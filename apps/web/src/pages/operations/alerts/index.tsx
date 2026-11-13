import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Row, Select, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AlertOutlined, BellOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import {
  getAlertStats,
  listAlerts,
  acknowledgeAlertsBatch,
  acknowledgeAlert,
  type AlertStats,
  type PlatformNotification
} from '@/api/system'
import { formatDateTime } from '@/utils/format'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'

const levelMeta: Record<string, { label: string; color: string }> = {
  critical: { label: '严重', color: 'red' },
  warning: { label: '警告', color: 'orange' },
  info: { label: '提示', color: 'blue' }
}

const sourceMeta: Record<string, string> = {
  quality: '数据质量',
  ingestion: '数据接入',
  workflow: '任务调度',
  metadata: '元数据服务'
}

export default function AlertCenterPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [alerts, setAlerts] = useState<PlatformNotification[]>([])
  const [stats, setStats] = useState<AlertStats>()
  const [loading, setLoading] = useState(false)
  const [acknowledging, setAcknowledging] = useState(false)
  const [level, setLevel] = useState<string>()
  const [source, setSource] = useState<string>()
  const [unreadOnly, setUnreadOnly] = useState(false)
  const pageSize = useResponsivePageSize(6, 5, 4, 3)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [list, stat] = await Promise.all([
        listAlerts({ level, source, unread: unreadOnly || undefined }),
        getAlertStats()
      ])
      setAlerts(list)
      setStats(stat)
    } catch {
      setAlerts([])
      setStats(undefined)
      message.error('告警数据加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, level, source, unreadOnly])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const handleMarkRead = async (alert: PlatformNotification) => {
    if (acknowledging) return
    setAcknowledging(true)
    try {
      await acknowledgeAlert(alert.id)
      message.success('已共享确认，其他管理员也会看到此状态')
      await refresh()
    } catch {
      message.error('标记失败')
    } finally {
      setAcknowledging(false)
    }
  }

  const handleMarkAll = async () => {
    if (acknowledging) return
    setAcknowledging(true)
    try {
      const result = await acknowledgeAlertsBatch()
      if (result.hasMore) message.info(`本次共享确认 ${result.acknowledged} 条，仍有待确认告警，可再次操作`)
      else message.success(`本次共享确认 ${result.acknowledged} 条，当前无待确认告警`)
      await refresh()
    } catch {
      message.error('批量标记失败')
    } finally {
      setAcknowledging(false)
    }
  }

  const columns: TableColumnsType<PlatformNotification> = [
    {
      title: '级别',
      dataIndex: 'level',
      key: 'level',
      width: 80,
      render: (value: PlatformNotification['level']) => {
        const meta = levelMeta[value] ?? { label: value, color: 'default' }
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 110,
      render: (value?: string) => (value ? sourceMeta[value] ?? value : '—')
    },
    {
      title: '告警内容',
      key: 'content',
      render: (_, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 'var(--font-body)' }}>{record.title}</Typography.Text>
          {record.message && (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 2, fontSize: 'var(--font-table)' }} ellipsis={{ rows: 2, expandable: true }}>
              {record.message}
            </Typography.Paragraph>
          )}
        </div>
      )
    },
    {
      title: '收件箱',
      dataIndex: 'recipient',
      key: 'recipient',
      width: 100,
      render: () => <Typography.Text>运维共享</Typography.Text>
    },
    {
      title: '状态',
      dataIndex: 'isRead',
      key: 'isRead',
      width: 90,
      render: (value?: boolean) => (value ? <Tag>已确认</Tag> : <Tag color="processing">待确认</Tag>)
    },
    {
      title: '时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '操作',
      key: 'actions',
      width: 160,
      render: (_, record) => (
        <>
          {record.linkPath && (
            <Button size="small" type="link" onClick={() => navigate(record.linkPath!)}>前往处理</Button>
          )}
          {!record.isRead && (
            <Button size="small" type="link" disabled={acknowledging} onClick={() => void handleMarkRead(record)}>共享确认</Button>
          )}
        </>
      )
    }
  ]

  return (
    <div className="alert-center-page">
      <PageHeader
        description="运维共享告警：需当前系统管理权限。确认状态对所有管理员生效，仅表示团队已知悉，不代表故障已修复或执行已恢复。"
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        }
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><StatCard title="严重告警" value={String(stats?.critical ?? 0)} icon={<AlertOutlined />} footer="需要立即关注" tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="警告" value={String(stats?.warning ?? 0)} icon={<BellOutlined />} footer="建议尽快处理" tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="提示" value={String(stats?.info ?? 0)} icon={<BellOutlined />} footer="知悉类信息" /></Col>
        <Col xs={12} lg={6}><StatCard title="告警总量" value={String(stats?.total ?? 0)} icon={<BellOutlined />} footer="全部级别合计" /></Col>
      </Row>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>级别</span>
            <Select
              placeholder="全部级别"
              allowClear
              value={level}
              onChange={setLevel}
              className="filter-control-sm"
              options={Object.entries(levelMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <div className="filter-item">
            <span>来源</span>
            <Select
              placeholder="全部来源"
              allowClear
              value={source}
              onChange={setSource}
              className="filter-control-sm"
              options={Object.entries(sourceMeta).map(([value, label]) => ({ value, label }))}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              value={unreadOnly ? 'unread' : undefined}
              onChange={(value) => setUnreadOnly(value === 'unread')}
              allowClear
              className="filter-control-sm"
              options={[{ value: 'unread', label: '仅待确认' }]}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
          <Tooltip title="每次最多确认 500 条全部来源的待确认告警；共享状态对所有管理员生效，不受当前列表过滤条件限制">
            <Button loading={acknowledging} onClick={() => void handleMarkAll()}>批量共享确认（最多500条）</Button>
          </Tooltip>
        </div>
      </PageToolbar>

      <DataSurface title="告警事件" description="最近 200 条" label="告警明细">
        <Table<PlatformNotification>
          rowKey="id"
          columns={columns}
          dataSource={alerts}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary">暂无告警。任务执行失败或质量检查未通过时，事件会出现在这里。</Typography.Text> }}
        />
      </DataSurface>
    </div>
  )
}
