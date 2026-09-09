import { Space, Tooltip, Typography } from 'antd'
import { LoadingOutlined } from '@ant-design/icons'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'
import type { ConnectionHealth, ConnectionHealthStatus as HealthStatus } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { connectionFailureDescription, effectiveConnectionStatus } from '../connectionHealth'

const labels: Record<HealthStatus, { label: string; tone: StatusTone }> = {
  unknown: { label: '未知', tone: 'neutral' },
  checking: { label: '探测中', tone: 'info' },
  available: { label: '可用', tone: 'success' },
  unavailable: { label: '不可用', tone: 'danger' }
}

export default function ConnectionHealthStatus({ health, now, testing = false }: {
  health?: ConnectionHealth
  now: number
  testing?: boolean
}) {
  const status = testing ? 'checking' : effectiveConnectionStatus(health, now)
  const meta = labels[status]
  const detail = status === 'checking'
    ? '正在执行轻量连接探测，请稍候。'
    : status === 'unknown'
      ? health?.failureCode === 'PROBE_REQUEST_FAILED'
        ? connectionFailureDescription(health)
        : health?.checkedAt ? '上次探测结果已过期或失效，请重新测试连接。' : '尚无有效探测结果，请测试连接。'
      : status === 'unavailable'
        ? connectionFailureDescription(health)
        : '最近一次轻量连接探测成功；业务查询耗时需单独查看。'

  return (
    <Space direction="vertical" size={2}>
      <Space size={6}>
        <StatusBadge tone={meta.tone}>{status === 'checking' && <LoadingOutlined />} {meta.label}</StatusBadge>
        {status !== 'checking' && status !== 'unknown' && health?.latencyMs != null && (
          <Typography.Text type="secondary">{health.latencyMs} ms</Typography.Text>
        )}
      </Space>
      <Typography.Text type="secondary">最近成功：{formatDateTime(health?.lastSuccessAt || undefined)}</Typography.Text>
      <Tooltip title={(
        <Space direction="vertical" size={2}>
          <span>最近探测：{formatDateTime(health?.checkedAt || undefined)}</span>
          {health?.failureCode && <span>失败代码：{health.failureCode}</span>}
          <span>{detail}</span>
        </Space>
      )}>
        <Typography.Text type={status === 'unavailable' ? 'danger' : 'secondary'} style={{ maxWidth: 200 }} ellipsis>
          {detail}
        </Typography.Text>
      </Tooltip>
    </Space>
  )
}
