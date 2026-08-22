import type { ReactNode } from 'react'

interface PageHeaderProps {
  title: string
  description?: string
  extra?: ReactNode
}

export default function PageHeader({ title, description, extra }: PageHeaderProps) {
  return (
    <div
      style={{
        display: 'flex',
        justifyContent: 'space-between',
        alignItems: 'flex-start',
        marginBottom: 16
      }}
    >
      <div>
        <h2 className="page-title">{title}</h2>
        {description && <p className="page-desc">{description}</p>}
      </div>
      {extra && <div>{extra}</div>}
    </div>
  )
}
