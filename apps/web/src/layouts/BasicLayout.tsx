import { Layout } from 'antd'
import { useState } from 'react'
import { Outlet, useLocation } from 'react-router-dom'
import HeaderBar from './components/HeaderBar'
import SideMenu from './components/SideMenu'

const { Content } = Layout

export default function BasicLayout() {
  const location = useLocation()
  const [railLocked, setRailLocked] = useState(false)

  return (
    <Layout className="app-shell">
      <HeaderBar />
      <aside className={`navigation-rail${railLocked ? ' rail-locked' : ''}`} aria-label="主导航" onMouseLeave={() => setRailLocked(false)}>
        <SideMenu onNavigate={() => setRailLocked(true)} />
      </aside>
      <Content className="app-content">
        <div className="content-frame"><div className="route-stage" key={location.pathname}><Outlet /></div></div>
      </Content>
    </Layout>
  )
}
