import type { ReactNode } from 'react'

interface DataSurfaceProps {
  children: ReactNode
  title?: ReactNode
  description?: ReactNode
  extra?: ReactNode
  className?: string
  label?: string
  style?: React.CSSProperties
}

export default function DataSurface({
  children,
  title,
  description,
  extra,
  className = '',
  label,
  style
}: DataSurfaceProps) {
  return (
    <section className={`data-surface${className ? ` ${className}` : ''}`} aria-label={label} style={style}>
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
