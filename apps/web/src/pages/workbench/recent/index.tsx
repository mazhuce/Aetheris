import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Empty, Popconfirm, Row, Tag, Typography } from 'antd'
import { ClockCircleOutlined, DeleteOutlined, RightOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { USERNAME_KEY } from '@/api/http'
import { clearRecentVisits, readRecentVisits, type RecentVisit } from '@/utils/recentVisits'
import { flattenNavItems } from '@/constants/navigation'

function relativeTime(timestamp: number): string {
  const diff = Date.now() - timestamp
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  const days = Math.floor(hours / 24)
  if (days < 30) return `${days} 天前`
  return `${Math.floor(days / 30)} 个月前`
}

export default function RecentVisitsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [visits, setVisits] = useState<RecentVisit[]>([])

  const refresh = useCallback(() => {
    setVisits(readRecentVisits(localStorage.getItem(USERNAME_KEY) ?? undefined))
  }, [])

  useEffect(() => {
    refresh()
  }, [refresh])

  const handleClear = () => {
    clearRecentVisits(localStorage.getItem(USERNAME_KEY) ?? undefined)
    message.success('已清空最近访问记录')
    refresh()
  }

  // 仍可用的页面才允许点击跳转；功能下线后显示为普通文本
  const availablePaths = new Set(flattenNavItems().map((item) => item.path))
  const todayCount = visits.filter((visit) => Date.now() - visit.visitedAt < 86_400_000).length

  return (
    <div className="recent-visits-page">
      <PageHeader
        description="你最近使用过的功能页，按访问时间倒序排列（仅记录本浏览器）。点击卡片可快速回到对应功能。"
        extra={
          visits.length > 0 ? (
            <Popconfirm title="清空最近访问记录？" onConfirm={handleClear}>
              <Button danger icon={<DeleteOutlined />}>清空记录</Button>
            </Popconfirm>
          ) : undefined
        }
      />

      <Row gutter={[16, 16]} style={{ marginBottom: 16 }}>
        <Col xs={12} lg={6}>
          <StatCard title="记录条数" value={String(visits.length)} icon={<ClockCircleOutlined />} footer="上限 20 条" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="今日访问" value={String(todayCount)} icon={<ClockCircleOutlined />} footer="24 小时内" tone="green" />
        </Col>
      </Row>

      <DataSurface title="访问足迹" description={visits.length ? `最近 ${visits.length} 个页面` : ''} label="最近访问列表">
        {visits.length ? (
          <Row gutter={[12, 12]} style={{ padding: 8 }}>
            {visits.map((visit) => {
              const active = availablePaths.has(visit.path)
              return (
                <Col xs={24} sm={12} lg={8} key={visit.path}>
                  <button
                    onClick={() => active && navigate(visit.path)}
                    disabled={!active}
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      alignItems: 'center',
                      width: '100%',
                      padding: '14px 16px',
                      borderRadius: 10,
                      border: '1px solid var(--border-color, #e5e7eb)',
                      background: 'var(--surface-color, #fafafa)',
                      cursor: active ? 'pointer' : 'default',
                      textAlign: 'left',
                      transition: 'box-shadow .15s ease',
                      opacity: active ? 1 : 0.55
                    }}
                  >
                    <div>
                      <div style={{ fontSize: 14, fontWeight: 600 }}>
                        {visit.label}
                        <Tag style={{ marginLeft: 10 }}>{visit.moduleLabel}</Tag>
                      </div>
                      <Typography.Text type="secondary" style={{ fontSize: 12 }}>
                        {relativeTime(visit.visitedAt)} · {visit.path}
                      </Typography.Text>
                    </div>
                    {active && <RightOutlined style={{ color: 'var(--text-tertiary)' }} />}
                  </button>
                </Col>
              )
            })}
          </Row>
        ) : (
          <Empty description="暂无访问记录，去左侧导航浏览几个功能页后再回来" />
        )}
      </DataSurface>
    </div>
  )
}
