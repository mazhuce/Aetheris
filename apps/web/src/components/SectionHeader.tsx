import type { ReactNode } from 'react'

interface SectionHeaderProps {
  title: ReactNode
  description?: ReactNode
  extra?: ReactNode
}

export default function SectionHeader({ title, description, extra }: SectionHeaderProps) {
  return (
    <header className="section-header">
      <div className="section-header-copy">
        <h2>{title}</h2>
        {description && <p>{description}</p>}
      </div>
      {extra && <div className="section-header-actions">{extra}</div>}
    </header>
  )
}
