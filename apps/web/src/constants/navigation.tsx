import type { ReactNode } from 'react'
import {
  ApartmentOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  DeploymentUnitOutlined,
  FileSearchOutlined,
  FolderOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined,
  ShareAltOutlined,
  UserOutlined
} from '@ant-design/icons'

export interface NavItem {
  key: string
  label: string
  icon: ReactNode
  path: string
}

export interface NavGroup {
  key: string
  label: string
  items: NavItem[]
}

export const navGroups: NavGroup[] = [
  {
    key: 'overview',
    label: '总览',
    items: [
      { key: 'dashboard', label: '工作台', icon: <DashboardOutlined />, path: '/dashboard' }
    ]
  },
  {
    key: 'data-asset',
    label: '数据资产',
    items: [
      { key: 'datasource', label: '数据源管理', icon: <DatabaseOutlined />, path: '/metadata/datasource' },
      { key: 'catalog', label: '元数据目录', icon: <FolderOutlined />, path: '/metadata/catalog' },
      { key: 'lineage', label: '数据血缘', icon: <ApartmentOutlined />, path: '/metadata/lineage' }
    ]
  },
  {
    key: 'data-dev',
    label: '数据开发',
    items: [
      { key: 'ingestion', label: '数据接入', icon: <ApiOutlined />, path: '/ingestion' },
      { key: 'quality', label: '数据质量', icon: <CheckCircleOutlined />, path: '/quality' },
      { key: 'governance', label: '数据治理', icon: <SafetyCertificateOutlined />, path: '/governance' },
      { key: 'workflow', label: '任务编排', icon: <DeploymentUnitOutlined />, path: '/workflow' }
    ]
  },
  {
    key: 'data-service',
    label: '数据服务',
    items: [
      { key: 'serving', label: '数据服务', icon: <ShareAltOutlined />, path: '/serving' },
      { key: 'api', label: 'API 管理', icon: <ApiOutlined />, path: '/serving/api' }
    ]
  },
  {
    key: 'ai',
    label: 'AI 平台',
    items: [
      { key: 'ai-agent', label: '智能助手', icon: <RobotOutlined />, path: '/ai' }
    ]
  },
  {
    key: 'system',
    label: '系统管理',
    items: [
      { key: 'users', label: '用户权限', icon: <UserOutlined />, path: '/system/users' },
      { key: 'config', label: '系统配置', icon: <SettingOutlined />, path: '/system/config' },
      { key: 'audit', label: '审计日志', icon: <FileSearchOutlined />, path: '/system/audit' }
    ]
  }
]

export function findNav(pathname: string): { group?: NavGroup; item?: NavItem } {
  for (const group of navGroups) {
    const item = group.items.find((entry) => entry.path === pathname)
    if (item) {
      return { group, item }
    }
  }
  return {}
}
