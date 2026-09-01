import { useLayoutEffect, useRef, useState } from 'react'
import { Avatar, Button, Dropdown } from 'antd'
import { DownOutlined, PlusOutlined, UserOutlined } from '@ant-design/icons'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { firstAvailablePath, findModule, navModules } from '@/constants/navigation'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '@/api/http'
import GlobalSearch from '@/components/GlobalSearch'
import BrandMark from '@/components/BrandMark'
import { getCurrentRole } from '@/utils/access'

/** 「更多」按钮预留宽度，用于计算一级模块能放下多少个。 */
const MORE_RESERVE = 64

export default function TopNav() {
  const location = useLocation()
  const navigate = useNavigate()
  const role = getCurrentRole()
  const username = localStorage.getItem(USERNAME_KEY) ?? '用户'

  const navRef = useRef<HTMLElement>(null)
  const itemRefs = useRef<(HTMLAnchorElement | null)[]>([])
  const naturalWidths = useRef<number[]>([])
  const [overflowAt, setOverflowAt] = useState(navModules.length)

  const activeKey = findModule(location.pathname)?.key

  // 记录每个模块标题的自然宽度（仅在首次全部渲染时测量一次，后续隐藏不影响）。
  useLayoutEffect(() => {
    naturalWidths.current = itemRefs.current.map((el) => el?.offsetWidth ?? 0)
  }, [])

  // 依据可用宽度计算能放下多少个模块，放不下的收进「更多」。
  useLayoutEffect(() => {
    const nav = navRef.current
    if (!nav) return

    const compute = () => {
      const available = nav.clientWidth
      const widths = naturalWidths.current
      if (available <= 0 || widths.length === 0) return

      const total = widths.reduce((sum, width) => sum + width, 0)
      if (total <= available) {
        setOverflowAt(navModules.length)
        return
      }

      let used = 0
      let fit = 0
      for (let i = 0; i < widths.length; i++) {
        if (used + widths[i] <= available - MORE_RESERVE) {
          used += widths[i]
          fit = i + 1
        } else {
          break
        }
      }
      setOverflowAt(Math.max(fit, 1))
    }

    compute()
    const observer = new ResizeObserver(compute)
    observer.observe(nav)
    return () => observer.disconnect()
  }, [])

  const handleLogout = () => {
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(ROLE_KEY)
    navigate('/login', { replace: true })
  }

  const visibleModules = navModules.slice(0, overflowAt)
  const overflowModules = navModules.slice(overflowAt)
  const overflowActive = overflowModules.some((module) => module.key === activeKey)

  return (
    <header className="topnav">
      <div className="topnav-brand">
        <Link to="/workbench" className="topnav-logo" aria-label="返回工作台">
          <BrandMark />
          <span className="topnav-wordmark">
            <strong>Aetheris</strong>
          </span>
        </Link>
      </div>

      <nav className="topnav-modules" ref={navRef} aria-label="主导航">
        {visibleModules.map((module, index) => (
          <Link
            key={module.key}
            ref={(el) => { itemRefs.current[index] = el }}
            to={firstAvailablePath(module, role)}
            className={`topnav-item${module.key === activeKey ? ' active' : ''}`}
          >
            <span className="topnav-item-icon">{module.icon}</span>
            <span className="topnav-item-label">{module.label}</span>
          </Link>
        ))}
        {overflowModules.length > 0 && (
          <Dropdown
            trigger={['click']}
            placement="bottom"
            menu={{
              items: overflowModules.map((module) => ({
                key: module.key,
                label: module.label,
                onClick: () => navigate(firstAvailablePath(module, role))
              })),
              selectedKeys: overflowActive ? [activeKey ?? ''] : []
            }}
          >
            <button
              className={`topnav-item topnav-more${overflowActive ? ' active' : ''}`}
              type="button"
              aria-label="打开更多主导航"
            >
              更多
              <DownOutlined className="topnav-more-icon" />
            </button>
          </Dropdown>
        )}
      </nav>

      <div className="topnav-actions">
        <GlobalSearch />
        <Dropdown
          menu={{
            items: [
              ...(role === 'admin' ? [{ key: 'source', label: '新建数据源' }] : []),
              { key: 'task', label: '新建任务' },
              ...(role === 'admin' ? [{ key: 'rule', label: '新建规则' }] : [])
            ],
            onClick: ({ key }) => {
              if (key === 'source') navigate('/integration/sources')
              if (key === 'task') navigate('/development/scheduling')
              if (key === 'rule') navigate('/governance/quality')
            }
          }}
        >
          <Button className="create-button" type="primary" icon={<PlusOutlined />} aria-label="新建内容">新建</Button>
        </Dropdown>
        <Dropdown
          menu={{
            items: [
              { key: 'identity', label: `${username} · ${role === 'admin' ? '管理员' : '只读用户'}`, disabled: true },
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
          <button className="account-button" type="button" aria-label="打开账户菜单">
            <Avatar size={28} icon={<UserOutlined />} />
            <span className="account-copy"><strong>{username}</strong></span>
          </button>
        </Dropdown>
      </div>
    </header>
  )
}
