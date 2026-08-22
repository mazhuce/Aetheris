import {
  Avatar,
  Badge,
  Breadcrumb,
  Button,
  Dropdown,
  Input,
  Select,
  Space
} from 'antd'
import {
  BellOutlined,
  MenuFoldOutlined,
  MenuUnfoldOutlined,
  QuestionCircleOutlined,
  SearchOutlined,
  UserOutlined
} from '@ant-design/icons'
import { useLocation } from 'react-router-dom'
import { findNav } from '@/constants/navigation'

interface HeaderBarProps {
  collapsed: boolean
  onToggle: () => void
}

export default function HeaderBar({ collapsed, onToggle }: HeaderBarProps) {
  const location = useLocation()
  const { group, item } = findNav(location.pathname)

  return (
    <div
      style={{
        height: 56,
        display: 'flex',
        alignItems: 'center',
        justifyContent: 'space-between',
        paddingInline: 16,
        background: '#fff',
        borderBottom: '1px solid #e5e6eb'
      }}
    >
      <Space size={12}>
        <Button
          type="text"
          icon={collapsed ? <MenuUnfoldOutlined /> : <MenuFoldOutlined />}
          onClick={onToggle}
        />
        <Breadcrumb
          items={[
            { title: 'Aetheris' },
            ...(group ? [{ title: group.label }] : []),
            ...(item ? [{ title: item.label }] : [])
          ]}
        />
      </Space>

      <Space size={16}>
        <Input
          prefix={<SearchOutlined />}
          placeholder="搜索产品、资源、文档"
          style={{ width: 240 }}
          allowClear
        />
        <Select
          value="cn-east-2"
          style={{ width: 140 }}
          options={[
            { value: 'cn-east-2', label: '华东2（上海）' },
            { value: 'cn-north-1', label: '华北1（青岛）' }
          ]}
        />
        <Badge count={3} size="small">
          <Button type="text" icon={<BellOutlined />} />
        </Badge>
        <Button type="text" icon={<QuestionCircleOutlined />} />
        <Dropdown
          menu={{
            items: [
              { key: 'profile', label: '个人中心' },
              { key: 'logout', label: '退出登录' }
            ]
          }}
        >
          <Space style={{ cursor: 'pointer' }}>
            <Avatar size={28} icon={<UserOutlined />} style={{ background: '#1677ff' }} />
            <span style={{ fontSize: 13 }}>admin</span>
          </Space>
        </Dropdown>
      </Space>
    </div>
  )
}
