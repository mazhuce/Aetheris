import type { ReactNode } from 'react'
import { Card } from 'antd'

export type Status = 'ok' | 'error' | 'pending'

interface MetricCardProps {
  icon: ReactNode
  label: string
  value: string
  status?: Status
  footer?: string
}

const statusText: Record<Status, string> = {
  ok: '在线',
  error: '离线',
  pending: '检测中'
}

export default function MetricCard({
  icon,
  label,
  value,
  status,
  footer
}: MetricCardProps) {
  return (
    <Card>
      <div
        style={{
          display: 'flex',
          alignItems: 'center',
          gap: 10,
          color: '#6b7280',
          marginBottom: 14
        }}
      >
        {icon}
        <span className="metric-label">{label}</span>
      </div>
      <div className="metric-value">
        {status && <span className={`status-dot ${status}`} />}
        {status ? statusText[status] : value}
      </div>
      {footer && <div className="metric-footer">{footer}</div>}
    </Card>
  )
}
