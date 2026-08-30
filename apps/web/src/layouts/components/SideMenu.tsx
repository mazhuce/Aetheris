import { Menu } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { navGroups } from '@/constants/navigation'
import { ROLE_KEY } from '@/api/http'

export default function SideMenu() {
  const location = useLocation()
  const navigate = useNavigate()
  const role = localStorage.getItem(ROLE_KEY)

  const items = navGroups.map((group) => ({
    type: 'group' as const,
    label: group.label,
    children: group.items
      .filter((item) => !item.requiredRole || item.requiredRole === role)
      .map((item) => ({
        key: item.path,
        icon: item.icon,
        label: item.label
      }))
  })).filter((group) => group.children.length > 0)

  return (
    <Menu
      mode="inline"
      className="side-menu"
      items={items}
      selectedKeys={[location.pathname]}
      onClick={({ key }) => navigate(key)}
      style={{ borderInlineEnd: 'none' }}
    />
  )
}
