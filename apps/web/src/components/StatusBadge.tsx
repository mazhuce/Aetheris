import { forwardRef, type ReactNode } from 'react'

export type StatusTone = 'neutral' | 'info' | 'success' | 'warning' | 'danger'

interface StatusBadgeProps {
  children: ReactNode
  tone?: StatusTone
  dot?: boolean
  className?: string
}

const StatusBadge = forwardRef<HTMLSpanElement, StatusBadgeProps>(function StatusBadge({
  children,
  tone = 'neutral',
  dot = true,
  className = ''
}, ref) {
  return (
    <span ref={ref} className={`status-badge is-${tone}${className ? ` ${className}` : ''}`}>
      {dot && <span className="status-badge-dot" aria-hidden="true" />}
      <span>{children}</span>
    </span>
  )
})

export default StatusBadge
