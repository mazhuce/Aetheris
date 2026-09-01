import type { ReactNode } from 'react'

interface PageToolbarProps {
  children: ReactNode
  actions?: ReactNode
  label?: string
  className?: string
}

export default function PageToolbar({
  children,
  actions,
  label = '页面工具栏',
  className = ''
}: PageToolbarProps) {
  return (
    <section className={`page-toolbar${className ? ` ${className}` : ''}`} aria-label={label}>
      <div className="page-toolbar-filters">{children}</div>
      {actions && <div className="page-toolbar-actions">{actions}</div>}
    </section>
  )
}
