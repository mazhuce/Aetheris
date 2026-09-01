import { Layout } from 'antd'
import { Outlet, useLocation } from 'react-router-dom'
import TopNav from './components/TopNav'
import SubNav from './components/SubNav'

const { Content } = Layout

export default function BasicLayout() {
  const location = useLocation()

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
