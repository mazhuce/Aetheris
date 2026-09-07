import { useLayoutEffect, useMemo, useRef, useState } from 'react'
import { Avatar, Button, Dropdown } from 'antd'
import { DownOutlined, PlusOutlined, UserOutlined } from '@ant-design/icons'
import { Link, useLocation, useNavigate } from 'react-router-dom'
import { availableNavModules, firstAvailablePath, findModule } from '@/constants/navigation'
import { ROLE_KEY, TOKEN_KEY, USERNAME_KEY } from '@/api/http'
import GlobalSearch from '@/components/GlobalSearch'
import BrandMark from '@/components/BrandMark'
import { getCurrentRole } from '@/utils/access'
import { logout } from '@/api/auth'
import { usePreferences } from '@/contexts/PreferencesContext'

/** 「更多」按钮预留宽度，用于计算一级模块能放下多少个。 */
const MORE_RESERVE = 64

export default function TopNav() {
  const location = useLocation()
  const navigate = useNavigate()
  const role = getCurrentRole()
  const { homePath } = usePreferences()
  const modules = useMemo(() => availableNavModules(role), [role])
  const username = localStorage.getItem(USERNAME_KEY) ?? '用户'

  const navRef = useRef<HTMLElement>(null)
  const itemRefs = useRef<(HTMLSpanElement | null)[]>([])
  const [overflowAt, setOverflowAt] = useState(modules.length)

  const activeKey = findModule(location.pathname)?.key

  // 依据可用宽度计算能放下多少个模块，放不下的收进「更多」。
  useLayoutEffect(() => {
    const nav = navRef.current
    if (!nav) return

    const compute = () => {
      const available = nav.clientWidth
      // 始终从不可见的完整菜单副本读取自然宽度。这样浏览器缩放或跨越
      // 响应式断点后，计算不会继续使用旧密度下的缓存宽度。
      const widths = itemRefs.current.map((el) => el?.offsetWidth ?? 0)
      if (available <= 0 || widths.length === 0) return

      const navStyle = getComputedStyle(nav)
      const gap = Number.parseFloat(navStyle.columnGap) || 0
      const horizontalPadding = (Number.parseFloat(navStyle.paddingLeft) || 0)
        + (Number.parseFloat(navStyle.paddingRight) || 0)
      const total = widths.reduce((sum, width) => sum + width, 0)
        + gap * Math.max(widths.length - 1, 0)
        + horizontalPadding
      if (total <= available) {
        setOverflowAt(modules.length)
        return
      }

      let used = horizontalPadding
      let fit = 0
      for (let i = 0; i < widths.length; i++) {
        const itemWidth = widths[i] + (i > 0 ? gap : 0)
        if (used + itemWidth <= available - MORE_RESERVE) {
          used += itemWidth
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
  }, [modules.length])

  const handleLogout = async () => {
    try {
      await logout()
    } catch {
    }
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(USERNAME_KEY)
    localStorage.removeItem(ROLE_KEY)
    navigate('/login', { replace: true })
  }

  const visibleModules = modules.slice(0, overflowAt)
  const overflowModules = modules.slice(overflowAt)
  const overflowActive = overflowModules.some((module) => module.key === activeKey)

  return (
    <header className="topnav">
      <div className="topnav-brand">
        <Link to={homePath} className="topnav-logo" aria-label="返回默认首页">
          <BrandMark />
          <span className="topnav-wordmark">
            <strong>Aetheris</strong>
          </span>
        </Link>
      </div>

      <nav className="topnav-modules" ref={navRef} aria-label="主导航">
        <div className="topnav-measure" aria-hidden="true">
          {modules.map((module, index) => (
            <span
              key={module.key}
              ref={(el) => { itemRefs.current[index] = el }}
              className="topnav-item"
            >
              <span className="topnav-item-icon">{module.icon}</span>
              <span className="topnav-item-label">{module.label}</span>
            </span>
          ))}
        </div>
        {visibleModules.map((module) => (
          <Link
            key={module.key}
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
        {role === 'admin' && (
          <Dropdown
            menu={{
              items: [
                { key: 'source', label: '新建数据源' },
                { key: 'task', label: '新建任务' },
                { key: 'rule', label: '新建规则' }
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
        )}
        <Dropdown
          menu={{
              items: [
                { key: 'identity', label: `${username} · ${role === 'admin' ? '管理员' : '只读用户'}`, disabled: true },
                { type: 'divider' },
                { key: 'settings', label: '系统设置' },
                { key: 'profile', label: '账户设置' },
                { type: 'divider' },
                { key: 'logout', label: '退出登录', danger: true }
              ],
              onClick: ({ key }) => {
                if (key === 'settings') navigate('/settings')
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
