import { Link, useLocation } from 'react-router-dom'
import { findChild } from '@/constants/navigation'
import { getCurrentRole } from '@/utils/access'

/** 当前模块的二级导航（横向 Tab 风格）。 */
export default function SubNav() {
  const location = useLocation()
  const role = getCurrentRole()
  const matched = findChild(location.pathname)
  if (!matched) return null

  const { module, child } = matched
  const children = module.children.filter((item) => !item.requiredRole || item.requiredRole === role)

  return (
    <div className="subnav">
      <div className="subnav-inner">
        <nav className="subnav-tabs" aria-label={`${module.label}二级导航`}>
          {children.map((item) => (
            <Link
              key={item.key}
              to={item.path}
              className={`subnav-item${item.key === child.key ? ' active' : ''}`}
            >
              {item.label}
              {item.status === 'comingSoon' && <span className="subnav-badge">规划中</span>}
            </Link>
          ))}
        </nav>
      </div>
    </div>
  )
}
