import { Menu } from 'antd'
import { useLocation, useNavigate } from 'react-router-dom'
import { navGroups } from '@/constants/navigation'

export default function SideMenu() {
  const location = useLocation()
  const navigate = useNavigate()

  const items = navGroups.map((group) => ({
    type: 'group' as const,
    label: group.label,
    children: group.items.map((item) => ({
      key: item.path,
      icon: item.icon,
      label: item.label
    }))
  }))

  return (
    <Menu
      theme="dark"
      mode="inline"
      items={items}
      selectedKeys={[location.pathname]}
      onClick={({ key }) => navigate(key)}
      style={{ borderInlineEnd: 'none' }}
    />
  )
}
