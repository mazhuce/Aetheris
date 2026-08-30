import { Avatar, Breadcrumb, Button, Dropdown, Input, Select, Space, Tooltip } from 'antd'
import {
  BellOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  SearchOutlined,
  UserOutlined
} from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { findNav } from '@/constants/navigation'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '@/api/http'

interface HeaderBarProps { collapsed: boolean; onToggle: () => void }

export default function HeaderBar({ collapsed, onToggle }: HeaderBarProps) {
  const location = useLocation()
  const navigate = useNavigate()
  const { group, item } = findNav(location.pathname)
  const username = localStorage.getItem(USERNAME_KEY) ?? '用户'
  const role = localStorage.getItem(ROLE_KEY) ?? 'viewer'

  const handleLogout = () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(ROLE_KEY)
    navigate('/login', { replace: true })
  }

  return (
    <header className="topbar">
      <Space size={10} className="topbar-context">
        <Tooltip title={collapsed ? '展开菜单' : '收起菜单'}>
          <Button className="icon-button" type="text" icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />} onClick={onToggle} />
        </Tooltip>
        <span className="topbar-divider" />
        <Breadcrumb
          items={[
            ...(group ? [{ title: group.label }] : []),
            ...(item ? [{ title: item.label }] : [])
          ]}
        />
      </Space>

      <div className="topbar-actions">
        <Input
          className="command-search"
          prefix={<SearchOutlined />}
          placeholder="搜索…"
          suffix={<kbd>⌘ K</kbd>}
          allowClear
        />
        <Select
          className="environment-select"
          value="production"
          options={[
            { value: 'production', label: 'Production' },
            { value: 'preview', label: 'Preview' },
            { value: 'development', label: 'Development' }
          ]}
        />
        <Tooltip title="通知"><Button className="icon-button" type="text" icon={<BellOutlined />} /></Tooltip>
        <Dropdown
          menu={{
            items: [
              { key: 'identity', label: `${username} · ${role}`, disabled: true },
              { type: 'divider' },
              { key: 'profile', label: '账户设置' },
              { key: 'logout', label: '退出登录', danger: true }
            ],
            onClick: ({ key }) => {
              if (key === 'logout') handleLogout()
            }
          }}
        >
          <button className="account-button"><Avatar size={26} icon={<UserOutlined />} /><span>{username}</span></button>
        </Dropdown>
      </div>
    </header>
  )
}
