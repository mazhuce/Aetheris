import type { ReactNode } from 'react'
import {
  ApiOutlined,
  BarChartOutlined,
  CloudServerOutlined,
  CodeOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FolderOpenOutlined,
  PartitionOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined
} from '@ant-design/icons'

/**
 * 一级能力域 + 二级菜单的统一导航配置（数据驱动）。
 *
 * 企业级智能数据中台按数据生命周期组织：数据接入 → 开发 → 建模 → 治理 → 资产 → 服务 →
 * 分析 → 智能中心，并由运维中心与系统管理提供平台级支撑。
 *
 * - status: 'available' 表示已有真实页面；'comingSoon' 为规范占位页。
 * - requiredRole: 仅 admin 可见的入口，与 <RequireRole> 路由门禁保持一致。
 * - description: 占位页的能力说明文案，正式产品感，避免开发者化措辞。
 */

export type NavStatus = 'available' | 'comingSoon'

export interface NavChild {
  key: string
  label: string
  path: string
  status: NavStatus
  requiredRole?: 'admin'
  description?: string
}

export interface NavModule {
  key: string
  label: string
  icon: ReactNode
  children: NavChild[]
}

export const navModules: NavModule[] = [
  {
    key: 'workbench',
    label: '工作台',
    icon: <DashboardOutlined />,
    children: [
      { key: 'overview', label: '数据概览', path: '/workbench', status: 'available' },
      { key: 'operations', label: '运行概览', path: '/workbench/operations', status: 'available' },
      { key: 'todos', label: '我的待办', path: '/workbench/todos', status: 'available' },
      { key: 'recent', label: '最近访问', path: '/workbench/recent', status: 'available' }
    ]
  },
  {
    key: 'integration',
    label: '数据接入',
    icon: <DatabaseOutlined />,
    children: [
      { key: 'sources', label: '数据源管理', path: '/integration/sources', status: 'available' },
      { key: 'sync', label: '数据同步', path: '/integration/sync', status: 'available' },
      { key: 'realtime', label: '实时采集', path: '/integration/realtime', status: 'comingSoon', description: '基于实时数据源的流式采集与增量接入。' },
      { key: 'tasks', label: '接入任务', path: '/integration/tasks', status: 'available' }
    ]
  },
  {
    key: 'development',
    label: '数据开发',
    icon: <CodeOutlined />,
    children: [
      { key: 'sql', label: 'SQL开发', path: '/development/sql', status: 'available' },
      { key: 'spark', label: 'Spark开发', path: '/development/spark', status: 'comingSoon', description: '提交与管理 Spark 作业，支持大数据量离线计算。' },
      { key: 'workflows', label: '工作流', path: '/development/workflows', status: 'available' },
      { key: 'scheduling', label: '任务调度', path: '/development/scheduling', status: 'available' },
      { key: 'releases', label: '发布管理', path: '/development/releases', status: 'available' }
    ]
  },
  {
    key: 'modeling',
    label: '数据建模',
    icon: <PartitionOutlined />,
    children: [
      { key: 'domains', label: '主题域', path: '/modeling/domains', status: 'available' },
      { key: 'dimensions', label: '维度管理', path: '/modeling/dimensions', status: 'available' },
      { key: 'metrics', label: '指标管理', path: '/modeling/metrics', status: 'available', description: '定义业务指标的计算口径、归属与版本。' },
      { key: 'logical-models', label: '逻辑模型', path: '/modeling/logical-models', status: 'available', description: '面向业务的概念与逻辑模型设计。' },
      { key: 'physical-models', label: '物理模型', path: '/modeling/physical-models', status: 'available' }
    ]
  },
  {
    key: 'governance',
    label: '数据治理',
    icon: <SafetyCertificateOutlined />,
    children: [
      { key: 'metadata', label: '元数据管理', path: '/governance/metadata', status: 'available' },
      { key: 'standards', label: '数据标准', path: '/governance/standards', status: 'available' },
      { key: 'quality', label: '数据质量', path: '/governance/quality', status: 'available' },
      { key: 'quality-trend', label: '质量趋势', path: '/governance/quality-trend', status: 'available' },
      { key: 'lineage', label: '数据血缘', path: '/governance/lineage', status: 'available' },
      { key: 'classification', label: '分类分级', path: '/governance/classification', status: 'available' },
      { key: 'issues', label: '治理问题', path: '/governance/issues', status: 'available' }
    ]
  },
  {
    key: 'assets',
    label: '数据资产',
    icon: <FolderOpenOutlined />,
    children: [
      { key: 'catalog', label: '资产目录', path: '/assets/catalog', status: 'available' },
      { key: 'tables', label: '数据表', path: '/assets/tables', status: 'available' },
      { key: 'datasets', label: '数据集', path: '/assets/datasets', status: 'available', description: '管理面向分析场景的、可复用的数据集。' },
      { key: 'metrics', label: '指标资产', path: '/assets/metrics', status: 'available' },
      { key: 'tags', label: '标签资产', path: '/assets/tags', status: 'available' },
      { key: 'apis', label: 'API资产', path: '/assets/apis', status: 'available' },
      { key: 'products', label: '数据产品', path: '/assets/products', status: 'available' }
    ]
  },
  {
    key: 'services',
    label: '数据服务',
    icon: <ApiOutlined />,
    children: [
      { key: 'apis', label: 'API管理', path: '/services/apis', status: 'comingSoon', description: '统一管理数据 API 的注册、发布与版本。' },
      { key: 'development', label: 'API开发', path: '/services/development', status: 'comingSoon', description: '将数据表、SQL 或指标编排为数据服务 API。' },
      { key: 'subscriptions', label: '数据订阅', path: '/services/subscriptions', status: 'available' },
      { key: 'authorization', label: '服务授权', path: '/services/authorization', status: 'available' },
      { key: 'monitoring', label: '服务监控', path: '/services/monitoring', status: 'comingSoon', description: '监控数据服务的调用、耗时与异常。' }
    ]
  },
  {
    key: 'analytics',
    label: '数据分析',
    icon: <BarChartOutlined />,
    children: [
      { key: 'query', label: '即席查询', path: '/analytics/query', status: 'available' },
      { key: 'visualization', label: '可视化分析', path: '/analytics/visualization', status: 'comingSoon', description: '以拖拽方式编排图表，完成数据可视化探索。' },
      { key: 'dashboards', label: '数据看板', path: '/analytics/dashboards', status: 'available', description: '编排与发布面向业务的运营看板。' },
      { key: 'history', label: '查询历史', path: '/analytics/history', status: 'available' }
    ]
  },
  {
    key: 'ai',
    label: '智能中心',
    icon: <RobotOutlined />,
    children: [
      { key: 'assistant', label: '数据助手', path: '/ai/assistant', status: 'available' },
      { key: 'chatbi', label: '智能问数', path: '/ai/chatbi', status: 'available' },
      { key: 'sql', label: 'SQL助手', path: '/ai/sql', status: 'comingSoon', description: '辅助编写、解释与优化 SQL。' },
      { key: 'governance', label: '治理助手', path: '/ai/governance', status: 'comingSoon', description: '辅助完成数据标准、质量与血缘等治理任务。' },
      { key: 'agents', label: 'Agent中心', path: '/ai/agents', status: 'comingSoon', description: '管理可编排的智能体与自动化流程。' }
    ]
  },
  {
    key: 'operations',
    label: '运维中心',
    icon: <CloudServerOutlined />,
    children: [
      { key: 'tasks', label: '任务监控', path: '/operations/tasks', status: 'available' },
      { key: 'services', label: '服务监控', path: '/operations/services', status: 'available' },
      { key: 'resources', label: '资源监控', path: '/operations/resources', status: 'comingSoon', description: '监控计算与存储资源的用量与水位。' },
      { key: 'alerts', label: '告警中心', path: '/operations/alerts', status: 'available' },
      { key: 'logs', label: '日志中心', path: '/operations/logs', status: 'comingSoon', description: '集中检索与查看平台运行日志。' },
      { key: 'audit', label: '审计日志', path: '/operations/audit', status: 'available', requiredRole: 'admin' }
    ]
  },
  {
    key: 'admin',
    label: '系统管理',
    icon: <SettingOutlined />,
    children: [
      { key: 'users', label: '用户管理', path: '/admin/users', status: 'available', requiredRole: 'admin' },
      { key: 'roles', label: '角色管理', path: '/admin/roles', status: 'comingSoon', description: '维护角色及其权限集合。' },
      { key: 'organizations', label: '组织管理', path: '/admin/organizations', status: 'comingSoon', description: '维护组织与部门结构。' },
      { key: 'permissions', label: '权限管理', path: '/admin/permissions', status: 'comingSoon', description: '配置功能与数据的访问权限。' },
      { key: 'workspaces', label: '项目空间', path: '/admin/workspaces', status: 'comingSoon', description: '按项目或团队划分隔离的工作空间。' },
      { key: 'settings', label: '系统配置', path: '/admin/settings', status: 'available' }
    ]
  }
]

/** 仅返回当前角色可使用且已经交付的导航，规划项仍保留在配置中供路线图维护。 */
export function availableNavModules(role?: 'admin' | 'viewer'): NavModule[] {
  return navModules
    .map((module) => ({
      ...module,
      children: module.children.filter(
        (child) => child.status === 'available' && (!child.requiredRole || child.requiredRole === role)
      )
    }))
    .filter((module) => module.children.length > 0)
}

/** 按路径首段匹配一级模块。 */
export function findModule(pathname: string): NavModule | undefined {
  const first = pathname.split('/')[1]
  return navModules.find((module) => module.key === first)
}

/** 按最长路径前缀匹配二级项（/governance/quality → 数据质量）。 */
export function findChild(pathname: string): { module: NavModule; child: NavChild } | undefined {
  const module = findModule(pathname)
  if (!module) return undefined
  let best: NavChild | undefined
  for (const child of module.children) {
    if (pathname === child.path || pathname.startsWith(child.path + '/')) {
      if (!best || child.path.length > best.path.length) best = child
    }
  }
  return best ? { module, child: best } : undefined
}

/** 当前模块下首个可访问的真实功能。 */
export function firstAvailablePath(module: NavModule, role?: 'admin' | 'viewer'): string {
  return module.children.find(
    (child) => child.status === 'available' && (!child.requiredRole || child.requiredRole === role)
  )?.path ?? '/workbench'
}

export interface FlatNavItem {
  key: string
  label: string
  path: string
  moduleLabel: string
  requiredRole?: 'admin'
}

/** 展平为一级/二级合并列表，供全局搜索使用。 */
export function flattenNavItems(): FlatNavItem[] {
  return navModules.flatMap((module) =>
    module.children.filter((child) => child.status === 'available').map((child) => ({
      key: `${module.key}-${child.key}`,
      label: child.label,
      path: child.path,
      moduleLabel: module.label,
      requiredRole: child.requiredRole
    }))
  )
}
