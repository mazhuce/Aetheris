import type { ReactNode } from 'react'

interface PageHeaderProps {
  title?: ReactNode
  description?: string
  extra?: ReactNode
  meta?: ReactNode
}

export default function PageHeader({ title, description, extra, meta }: PageHeaderProps) {
  return (
    <header className="page-header-shell">
      <div className="page-header-copy">
        {title && <h1>{title}</h1>}
        {description && <p className="page-description">{description}</p>}
        {meta && <div className="page-meta">{meta}</div>}
      </div>
      {extra && <div className="page-header-actions">{extra}</div>}
    </header>
  )
}
