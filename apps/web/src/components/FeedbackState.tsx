import type { ReactNode } from 'react'
import { Button, Skeleton } from 'antd'
import { InboxOutlined, WarningOutlined } from '@ant-design/icons'

interface EmptyStateProps {
  title: string
  description?: string
  action?: ReactNode
  icon?: ReactNode
  compact?: boolean
}

export function EmptyState({ title, description, action, icon, compact = false }: EmptyStateProps) {
  return (
    <div className={`feedback-state${compact ? ' is-compact' : ''}`}>
      <span className="feedback-state-icon" aria-hidden="true">{icon ?? <InboxOutlined />}</span>
      <strong>{title}</strong>
      {description && <p>{description}</p>}
      {action && <div className="feedback-state-action">{action}</div>}
    </div>
  )
}

interface ErrorStateProps {
  title?: string
  description: string
  onRetry?: () => void
}

export function ErrorState({ title = '无法加载内容', description, onRetry }: ErrorStateProps) {
  return (
    <div className="feedback-state is-error" role="alert">
      <span className="feedback-state-icon" aria-hidden="true"><WarningOutlined /></span>
      <strong>{title}</strong>
      <p>{description}</p>
      {onRetry && <Button onClick={onRetry}>重新加载</Button>}
    </div>
  )
}

export function LoadingState({ rows = 5 }: { rows?: number }) {
  return (
    <div className="loading-state" aria-label="正在加载">
      {Array.from({ length: rows }).map((_, index) => (
        <Skeleton.Input key={index} active block size="small" />
      ))}
    </div>
  )
}
