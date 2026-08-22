import { useState } from 'react'
import { Layout, Menu, Tag, Typography } from 'antd'
import {
  DashboardOutlined,
  ApiOutlined,
  DatabaseOutlined,
  CheckCircleOutlined,
  SafetyCertificateOutlined,
  ShareAltOutlined,
  RobotOutlined
} from '@ant-design/icons'
import Dashboard from '../pages/Dashboard'

const { Sider, Header, Content } = Layout

const navItems = [
  { key: 'overview', icon: <DashboardOutlined />, label: '总览' },
  { key: 'ingestion', icon: <ApiOutlined />, label: '数据接入' },
  { key: 'metadata', icon: <DatabaseOutlined />, label: '元数据管理' },
  { key: 'quality', icon: <CheckCircleOutlined />, label: '数据质量' },
  { key: 'governance', icon: <SafetyCertificateOutlined />, label: '数据治理' },
  { key: 'serving', icon: <ShareAltOutlined />, label: '服务开放' },
  { key: 'ai', icon: <RobotOutlined />, label: 'AI 能力' }
]

const titleMap: Record<string, string> = {
  overview: '总览',
  ingestion: '数据接入',
  metadata: '元数据管理',
  quality: '数据质量',
  governance: '数据治理',
  serving: '服务开放',
  ai: 'AI 能力'
}

export default function AppLayout() {
  const [active, setActive] = useState('overview')

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider
        width={220}
        style={{ borderRight: '1px solid #e5e7eb', position: 'relative' }}
      >
        <div className="sider-brand">
          <span className="sider-logo" />
          <span>Aetheris</span>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[active]}
          items={navItems}
          onClick={({ key }) => setActive(key)}
          style={{ borderInlineEnd: 'none' }}
        />
        <div className="sider-foot">数据中台 · v0.1.0</div>
      </Sider>

      <Layout>
        <Header
          style={{
            height: 56,
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'space-between',
            paddingInline: 24,
            borderBottom: '1px solid #e5e7eb'
          }}
        >
          <Typography.Text strong>{titleMap[active]}</Typography.Text>
          <Tag color="blue">本地环境</Tag>
        </Header>

        <Content style={{ padding: 24 }}>
          {active === 'overview' ? (
            <Dashboard />
          ) : (
            <div className="placeholder">该模块尚未开放，正在按阶段建设中。</div>
          )}
        </Content>
      </Layout>
    </Layout>
  )
}
