import type { ReactNode } from 'react'
import { useInRouterContext, useLocation } from 'react-router-dom'
import { findChildIn, navModules } from '@/constants/navigation'

interface PageHeaderProps {
  title?: ReactNode
  description?: string
  extra?: ReactNode
  meta?: ReactNode
}

/**
 * 没有显式 title 时，用当前路由在菜单树里的名称兜底。
 *
 * 查的是出厂菜单树而不是用户当前配置：把某个一级菜单隐藏掉以后，
 * 它下面的页面仍然应该显示标题，只是入口不在顶栏而已。
 *
 * 单独拆成组件是有意为之：useLocation 在 Router 之外会抛错，
 * 而部分单测会脱离路由直接渲染页面。这里只在确实处于路由上下文时才挂载，
 * 既不违反 hook 规则，也不会让页面在测试里炸掉。
 */
function RouteTitle() {
  const { pathname } = useLocation()
  const matched = findChildIn(navModules, pathname)
  if (!matched) return null
  return <h1>{matched.child.label}</h1>
}

export default function PageHeader({ title, description, extra, meta }: PageHeaderProps) {
  const inRouter = useInRouterContext()

  return (
    <header className="page-header-shell">
      <div className="page-header-copy">
        {title ? <h1>{title}</h1> : inRouter ? <RouteTitle /> : null}
        {description && <p className="page-description">{description}</p>}
        {meta && <div className="page-meta">{meta}</div>}
      </div>
      {extra && <div className="page-header-actions">{extra}</div>}
    </header>
  )
}
