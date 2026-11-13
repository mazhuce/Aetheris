import { Link, useLocation } from 'react-router-dom'
import { useNavigation } from '@/contexts/NavigationContext'
import { prefetchRoute } from '@/router/prefetch'

/** 当前模块的二级导航（横向 Tab 风格）。 */
export default function SubNav() {
  const location = useLocation()
  const { findChild } = useNavigation()
  const matched = findChild(location.pathname)
  if (!matched) return null

  const { module, child } = matched

  return (
    <div className="subnav">
      <div className="subnav-inner">
        <nav className="subnav-tabs" aria-label={`${module.label}二级导航`}>
          {module.children.map((item) => (
            <Link
              key={item.key}
              to={item.path}
              onMouseEnter={() => prefetchRoute(item.path)}
              onFocus={() => prefetchRoute(item.path)}
              className={`subnav-item${item.key === child.key ? ' active' : ''}`}
            >
              {item.label}
            </Link>
          ))}
        </nav>
      </div>
    </div>
  )
}
