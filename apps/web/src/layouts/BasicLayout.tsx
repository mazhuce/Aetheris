import { useEffect } from 'react'
import { Layout } from 'antd'
import { Outlet, useLocation } from 'react-router-dom'
import TopNav from './components/TopNav'
import SubNav from './components/SubNav'
import { findChild } from '@/constants/navigation'
import { getAuthSnapshot } from '@/auth/authState'
import { recordRecentVisit } from '@/utils/recentVisits'

const { Content } = Layout

export default function BasicLayout() {
  const location = useLocation()

  // 最近访问记录：仅记录导航配置中的真实功能页，编辑器/预览等深层路由不记
  useEffect(() => {
    const matched = findChild(location.pathname)
    const session = getAuthSnapshot()
    if (matched && matched.child.status === 'available') {
      recordRecentVisit(
        matched.child.path,
        matched.child.label,
        matched.module.label,
        session.status === 'authenticated' ? session.user.username : undefined
      )
    }
  }, [location.pathname])

  return (
    <Layout className="app-shell">
      <TopNav />
      <SubNav />
      <Content className="app-content">
        <div className="content-frame">
          <div className="route-stage" key={location.pathname}><Outlet /></div>
        </div>
      </Content>
    </Layout>
  )
}
