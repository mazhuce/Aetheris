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

const toneColors = { blue: '#071a3d', green: '#071a3d', orange: '#071a3d', purple: '#071a3d' }

export default function StatCard({ title, value, icon, trend, footer, tone = 'blue' }: StatCardProps) {
  const isUp = (trend ?? 0) >= 0

  return (
    <Card size="small">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
        <div>
          <div className="stat-label">{title}</div>
          <div className="stat-value">{value}</div>
          {typeof trend === 'number' && (
            <div style={{ marginTop: 4, fontSize: 12, color: '#071a3d' }}>
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
