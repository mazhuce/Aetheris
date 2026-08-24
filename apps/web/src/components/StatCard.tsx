import { Card } from 'antd'
import type { ReactNode } from 'react'

interface StatCardProps {
  title: string
  value: string
  icon: ReactNode
  trend?: number
  footer?: string
  tone?: 'blue' | 'green' | 'orange' | 'purple'
}

const toneColors = { blue: '#1677ff', green: '#16a34a', orange: '#ea580c', purple: '#7c3aed' }

export default function StatCard({ title, value, icon, trend, footer, tone = 'blue' }: StatCardProps) {
  const isUp = (trend ?? 0) >= 0

  return (
    <Card size="small">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <div className="stat-label">{title}</div>
          <div className="stat-value">{value}</div>
          {typeof trend === 'number' && (
            <div style={{ marginTop: 4, fontSize: 12, color: isUp ? '#52c41a' : '#ff4d4f' }}>
              {isUp ? '▲' : '▼'} {Math.abs(trend)}% 较昨日
            </div>
          )}
        </div>
        <div style={{ color: toneColors[tone], fontSize: 20 }}>{icon}</div>
      </div>
      {footer && <div className="stat-footer">{footer}</div>}
    </Card>
  )
}
