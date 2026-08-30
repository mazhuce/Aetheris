import type { ReactNode } from 'react'

interface PageHeaderProps {
  eyebrow?: string
  title: string
  description?: string
  extra?: ReactNode
}

export default function PageHeader({ eyebrow, title, description, extra }: PageHeaderProps) {
  return (
    <header className="page-header-shell">
      <div className="page-header-copy">
        {eyebrow && <div className="page-eyebrow">{eyebrow}</div>}
        <h2 className="page-title">{title}</h2>
        {description && <p className="page-desc">{description}</p>}
      </div>
      {extra && <div className="page-header-actions">{extra}</div>}
    </header>
  )
}
