import { NavLink } from 'react-router-dom'
import { navGroups } from '@/constants/navigation'
import { ROLE_KEY } from '@/api/http'
import BrandMark from '@/components/BrandMark'

interface SideMenuProps { onNavigate?: () => void }

export default function SideMenu({ onNavigate }: SideMenuProps) {
  const role = localStorage.getItem(ROLE_KEY)

  return (
    <div className="rail-inner">
      <div className="rail-brand"><BrandMark /><span className="rail-label brand-name">Aetheris</span></div>
      <nav className="rail-navigation">
        {navGroups.map((group) => {
          const items = group.items.filter((item) => !item.requiredRole || item.requiredRole === role)
          if (!items.length) return null
          return (
            <div className="rail-group" key={group.key}>
              <div className="rail-group-title"><span className="rail-label">{group.label}</span></div>
              {items.map((item) => (
                <NavLink key={item.key} to={item.path} onClick={(event) => { onNavigate?.(); event.currentTarget.blur() }} className={({ isActive }) => `rail-item${isActive ? ' active' : ''}`} title={item.label}>
                  <span className="rail-icon">{item.icon}</span>
                  <span className="rail-label">{item.label}</span>
                  <span className="rail-arrow">›</span>
                </NavLink>
              ))}
            </div>
          )
        })}
      </nav>
      <div className="rail-status"><span className="status-dot" /><span className="rail-label">系统运行正常</span></div>
    </div>
  )
}
