import { useState } from 'react'
import { Layout } from 'antd'
import { Outlet } from 'react-router-dom'
import HeaderBar from './components/HeaderBar'
import SideMenu from './components/SideMenu'

const { Sider, Content } = Layout

export default function BasicLayout() {
  const [collapsed, setCollapsed] = useState(false)

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider theme="dark" width={220} collapsible collapsed={collapsed} trigger={null}>
        <div className="app-logo">
          <span className="logo-mark" />
          {!collapsed && <span>Aetheris 数据平台</span>}
        </div>
        <SideMenu />
      </Sider>
      <Layout>
        <HeaderBar collapsed={collapsed} onToggle={() => setCollapsed((value) => !value)} />
        <Content style={{ padding: 16 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
