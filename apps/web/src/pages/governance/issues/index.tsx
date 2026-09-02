import { useCallback, useEffect, useState } from 'react'
import { App, Button, Popconfirm, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { BugOutlined, CheckCircleOutlined, ExclamationCircleOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { getAlertStats, listAlerts, markNotificationRead, type AlertStats, type PlatformNotification } from '@/api/system'
import { formatDateTime } from '@/utils/format'

const sourceMeta: Record<string, { label: string; path: string }> = {
  quality: { label: '数据质量', path: '/governance/quality' },
  ingestion: { label: '数据接入', path: '/integration/tasks' },
  workflow: { label: '任务调度', path: '/operations/tasks' },
  system: { label: '平台系统', path: '/operations/services' }
}

const levelMeta: Record<string, { label: string; color: string }> = {
  critical: { label: '严重', color: 'red' },
  warning: { label: '警告', color: 'orange' },
  info: { label: '提示', color: 'blue' }
}

export default function GovernanceIssuesPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [issues, setIssues] = useState<PlatformNotification[]>([])
  const [stats, setStats] = useState<AlertStats>()
  const [loading, setLoading] = useState(false)
  const [source, setSource] = useState<string>()
  const [unreadOnly, setUnreadOnly] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      // 治理问题视角：只看质量与接入产生的事件（问题来源），调度/系统类事件在告警中心
      const [list, stat] = await Promise.all([
        listAlerts({ source, unread: unreadOnly || undefined }),
        getAlertStats()
      ])
      const governanceIssues = list.filter(
        (item) => item.source === 'quality' || item.source === 'ingestion' || source === item.source
      )
      setIssues(governanceIssues)
      setStats(stat)
    } catch {
      message.error('治理问题加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, source, unreadOnly])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const handleResolve = async (issue: PlatformNotification) => {
    try {
      await markNotificationRead(issue.id)
      message.success('问题已标记为闭环')
      await refresh()
    } catch {
      message.error('标记失败')
    }
  }

  const openCount = issues.filter((issue) => !issue.isRead).length

  const columns: TableColumnsType<PlatformNotification> = [
    {
      title: '问题级别',
      dataIndex: 'level',
      key: 'level',
      width: 90,
      render: (value: PlatformNotification['level']) => {
        const meta = levelMeta[value] ?? { label: value, color: 'default' }
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '问题来源',
      dataIndex: 'source',
      key: 'source',
      width: 110,
      render: (value?: string) => {
        if (!value) return '—'
        const meta = sourceMeta[value]
        return meta ? <Tag>{meta.label}</Tag> : <Tag>{value}</Tag>
      }
    },
    {
      title: '问题描述',
      key: 'content',
      render: (_, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 13 }}>{record.title}</Typography.Text>
          {record.message && (
            <Typography.Paragraph type="secondary" style={{ marginBottom: 0, marginTop: 2, fontSize: 12 }} ellipsis={{ rows: 2, expandable: true }}>
              {record.message}
            </Typography.Paragraph>
          )}
        </div>
      )
    },
    {
      title: '处置状态',
      dataIndex: 'isRead',
      key: 'isRead',
      width: 100,
      render: (value?: boolean) => (value ? <Tag color="success">已闭环</Tag> : <Tag color="processing">待处置</Tag>)
    },
    {
      title: '发现时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 160,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_, record) => (
        <>
          {record.source && sourceMeta[record.source] && (
            <Button
              size="small"
              type="link"
              onClick={() => navigate(sourceMeta[record.source].path)}
            >
              前去处置
            </Button>
          )}
          {!record.isRead && (
            <Popconfirm title="确认该问题已处置完成？" onConfirm={() => void handleResolve(record)}>
              <Button size="small" type="link">标记闭环</Button>
            </Popconfirm>
          )}
        </>
      )
    }
  ]

  return (
    <div className="governance-issues-page">
      <PageHeader
        description="数据治理问题台账：集中跟踪数据质量检查未通过与接入任务失败产生的问题，按发现 → 处置 → 闭环管理问题生命周期。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <div className="stat-grid" style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16, marginBottom: 16 }}>
        <StatCard title="待处置问题" value={String(openCount)} icon={<BugOutlined />} footer="需要人工介入" tone="orange" />
        <StatCard title="严重问题" value={String(stats?.critical ?? 0)} icon={<ExclamationCircleOutlined />} footer="含全部来源" tone="orange" />
        <StatCard title="警告问题" value={String(stats?.warning ?? 0)} icon={<ExclamationCircleOutlined />} footer="建议尽快处理" />
        <StatCard title="已闭环" value={String(issues.length - openCount)} icon={<CheckCircleOutlined />} footer="当前视图内" tone="green" />
      </div>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>来源</span>
            <Select
              placeholder="全部来源"
              allowClear
              value={source}
              onChange={setSource}
              className="filter-control-sm"
              options={Object.entries(sourceMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              value={unreadOnly ? 'open' : undefined}
              onChange={(value) => setUnreadOnly(value === 'open')}
              allowClear
              className="filter-control-sm"
              options={[{ value: 'open', label: '仅待处置' }]}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
        </div>
      </PageToolbar>

      <DataSurface title="问题台账" description="最近 200 条治理事件" label="治理问题明细">
        <Table<PlatformNotification>
          rowKey="id"
          columns={columns}
          dataSource={issues}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary">暂无治理问题。质量检查全部通过、接入任务运行平稳时，这里会是空的。</Typography.Text> }}
        />
      </DataSurface>
    </div>
  )
}
