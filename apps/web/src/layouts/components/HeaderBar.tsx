import { Avatar, Breadcrumb, Button, Dropdown, Space } from 'antd'
import { PlusOutlined, UserOutlined } from '@ant-design/icons'
import { useLocation, useNavigate } from 'react-router-dom'
import { findNav } from '@/constants/navigation'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '@/api/http'
import GlobalSearch from '@/components/GlobalSearch'

export default function HeaderBar() {
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
        <Breadcrumb
          items={[
            { title: 'Workspace' },
            ...(group ? [{ title: group.label }] : []),
            ...(item ? [{ title: item.label }] : [])
          ]}
        />
      </Space>

      <div className="topbar-actions">
        <GlobalSearch />
        <Dropdown menu={{ items: [{ key: 'source', label: '新建数据源' }, { key: 'task', label: '新建任务' }, { key: 'rule', label: '新建规则' }], onClick: ({ key }) => { if (key === 'source') navigate('/metadata/datasource'); if (key === 'task') navigate('/workflow'); if (key === 'rule') navigate('/quality/rules') } }}>
          <Button className="create-button" type="primary" icon={<PlusOutlined />}>新建</Button>
        </Dropdown>
        <Dropdown
          menu={{
            items: [
              { key: 'identity', label: `${username} · ${role}`, disabled: true },
              { type: 'divider' },
              { key: 'profile', label: '账户设置' },
              { key: 'logout', label: '退出登录', danger: true }
            ],
            onClick: ({ key }) => {
              if (key === 'profile') navigate('/account')
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
