import type { ReactNode } from 'react'

interface DataSurfaceProps {
  children: ReactNode
  title?: ReactNode
  description?: ReactNode
  extra?: ReactNode
  className?: string
  label?: string
}

export default function DataSurface({
  children,
  title,
  description,
  extra,
  className = '',
  label
}: DataSurfaceProps) {
  return (
    <section className={`data-surface${className ? ` ${className}` : ''}`} aria-label={label}>
      {(title || description || extra) && (
        <div className="section-header">
          <div className="section-header-copy">
            {title && <h2>{title}</h2>}
            {description && <p>{description}</p>}
          </div>
          {extra && <div className="section-header-actions">{extra}</div>}
        </div>
      )}
      <div className="data-surface-content">{children}</div>
    </section>
  )
}
