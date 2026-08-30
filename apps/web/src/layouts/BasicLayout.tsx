import { useState } from 'react'
import { Layout } from 'antd'
import { Outlet } from 'react-router-dom'
import HeaderBar from './components/HeaderBar'
import SideMenu from './components/SideMenu'

const { Sider, Content } = Layout

export default function BasicLayout() {
  const [collapsed, setCollapsed] = useState(false)

  return (
    <Layout className="app-shell">
      <Sider className="app-sidebar" width={232} collapsedWidth={72} collapsed={collapsed} trigger={null}>
        <div className="app-logo">
          <span className="brand-mark">A</span>
          {!collapsed && <span className="brand-name">Aetheris</span>}
        </div>
        <SideMenu />
        {!collapsed && <div className="sidebar-footer"><span className="status-dot" /> All systems operational</div>}
      </Sider>
      <Layout className="app-workspace">
        <HeaderBar collapsed={collapsed} onToggle={() => setCollapsed((value) => !value)} />
        <Content className="app-content"><div className="content-frame"><Outlet /></div></Content>
      </Layout>
    </Layout>
  )
}
