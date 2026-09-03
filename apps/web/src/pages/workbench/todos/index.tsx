import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { App, Badge, Button, List, Segmented, Space, Tag, Typography } from 'antd'
import { BellOutlined, CheckOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { listNotifications, markAllNotificationsRead, markNotificationRead, type PlatformNotification } from '@/api/system'
import { formatDateTime } from '@/utils/format'

const sourceMeta: Record<string, { label: string; color: string }> = {
  quality: { label: '数据质量', color: 'blue' },
  ingestion: { label: '数据接入', color: 'cyan' },
  workflow: { label: '任务调度', color: 'purple' },
  system: { label: '系统', color: 'default' }
}

const levelMeta: Record<string, { tone: 'success' | 'warning' | 'danger'; text: string }> = {
  info: { tone: 'success', text: '提示' },
  warning: { tone: 'warning', text: '警告' },
  critical: { tone: 'danger', text: '严重' }
}

export default function TodosPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [notifications, setNotifications] = useState<PlatformNotification[]>([])
  const [loading, setLoading] = useState(false)
  const [filter, setFilter] = useState<'all' | 'unread'>('all')

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setNotifications(await listNotifications(filter === 'unread' ? true : undefined))
    } catch {
      message.error('待办加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, filter])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const unreadCount = useMemo(() => notifications.filter((item) => !item.isRead).length, [notifications])

  const handleMarkRead = async (id: number) => {
    try {
      await markNotificationRead(id)
      setNotifications((prev) => prev.map((item) => (item.id === id ? { ...item, isRead: true } : item)))
    } catch {
      message.error('操作失败')
    }
  }

  const handleMarkAllRead = async () => {
    try {
      await markAllNotificationsRead()
      await refresh()
      message.success('已全部标记为已读')
    } catch {
      message.error('操作失败')
    }
  }

  return (
    <div className="todos-page">
      <PageHeader
        description="汇总质量检查告警、接入与调度任务失败等与当前账号相关的待处理事项。"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
            <Button icon={<CheckOutlined />} disabled={unreadCount === 0} onClick={() => void handleMarkAllRead()}>
              全部已读
            </Button>
          </Space>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <Segmented
              value={filter}
              onChange={(value) => setFilter(value as 'all' | 'unread')}
              options={[
                { value: 'all', label: '全部' },
                { value: 'unread', label: <Badge count={unreadCount} size="small" offset={[8, 0]}>未读</Badge> }
              ]}
            />
          </div>
        </div>
      </PageToolbar>

      <DataSurface title="待办列表" description="最近 200 条通知" label="我的待办">
        <List<PlatformNotification>
          loading={loading}
          dataSource={notifications}
          locale={{ emptyText: '暂无待办。质量检查告警与任务失败会自动出现在这里。' }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          renderItem={(item) => {
            const source = sourceMeta[item.source] ?? { label: item.source, color: 'default' }
            const level = levelMeta[item.level] ?? levelMeta.info
            return (
              <List.Item
                actions={[
                  item.isRead ? (
                    <Typography.Text key="read" type="secondary">已读</Typography.Text>
                  ) : (
                    <Button key="mark" type="link" size="small" onClick={() => void handleMarkRead(item.id)}>
                      标记已读
                    </Button>
                  )
                ]}
              >
                <List.Item.Meta
                  avatar={<BellOutlined style={{ fontSize: 20, color: item.isRead ? 'var(--text-disabled)' : '#1677ff' }} />}
                  title={
                    <Space size={8} wrap>
                      {!item.isRead && <Badge status="processing" />}
                      <span style={item.isRead ? { color: 'var(--text-tertiary)' } : undefined}>{item.title}</span>
                      <Tag color={source.color}>{source.label}</Tag>
                      <StatusBadge tone={level.tone}>{level.text}</StatusBadge>
                    </Space>
                  }
                  description={
                    <Space direction="vertical" size={2}>
                      <span>{item.message || '—'}</span>
                      <Typography.Text type="secondary">
                        {formatDateTime(item.createdAt)}
                        {item.linkPath && (
                          <Typography.Link
                            style={{ marginLeft: 12 }}
                            onClick={() => navigate(item.linkPath!)}
                          >
                            前往处理
                          </Typography.Link>
                        )}
                      </Typography.Text>
                    </Space>
                  }
                />
              </List.Item>
            )
          }}
        />
      </DataSurface>
    </div>
  )
}
