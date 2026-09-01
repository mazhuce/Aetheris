import { Card } from 'antd'
import type { ReactNode } from 'react'

interface StatCardProps {
  title: string
  value: string
  icon: ReactNode
  trend?: number
  footer?: string
  tone?: 'neutral' | 'green' | 'orange'
}

export default function StatCard({ title, value, icon, trend, footer, tone = 'neutral' }: StatCardProps) {
  const isUp = (trend ?? 0) >= 0

  return (
    <Card size="small" className={`stat-card stat-card-${tone}`}>
      <div className="stat-card-main">
        <div>
          <div className="stat-label">{title}</div>
          <div className="stat-value">{value}</div>
          {typeof trend === 'number' && (
            <div className={`stat-trend ${isUp ? 'is-up' : 'is-down'}`}>
              {isUp ? '▲' : '▼'} {Math.abs(trend)}% 较昨日
            </div>
          )}
        </div>
        <div className="stat-icon">{icon}</div>
      </div>
      {footer && <div className="stat-footer">{footer}</div>}
    </Card>
  )
}
