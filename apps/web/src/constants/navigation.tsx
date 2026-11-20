import type { ReactNode } from 'react'
import {
  BarChartOutlined,
  CloudServerOutlined,
  CodeOutlined,
  DashboardOutlined,
  DatabaseOutlined,
  FolderOpenOutlined,
  IdcardOutlined,
  PartitionOutlined,
  AppstoreOutlined,
  ReadOutlined,
  RobotOutlined,
  SafetyCertificateOutlined,
  SettingOutlined
} from '@ant-design/icons'

/**
 * 一级能力域 + 二级菜单的默认配置（数据驱动）。
 *
 * 这里只是「出厂默认」。运行时的真实菜单由 resolveNavigation() 把本文件与
 * 用户在「系统设置 → 菜单配置」里的覆盖项合并得到，因此：
 * - 本文件负责稳定的默认归属与顺序，不要在这里做角色判断；
 * - 任何用户级调整（隐藏、改归属、改顺序）都落到 NavigationConfig，不写回这里。
 *
 * 约定：
 * - status: 'available' 表示已有真实页面；'comingSoon' 为规划项，不进导航。
 * - requiredRole: 仅 admin 可见，与 <RequireRole> 路由门禁保持一致。
 * - defaultVisible: false 表示默认不占顶栏位置，但仍可在菜单配置里打开。
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
  /** 学习者视图下的一级菜单名称；缺省沿用 label。 */
  learnerLabel?: string
  icon: ReactNode
  /** 默认是否出现在顶栏；缺省为 true。 */
  defaultVisible?: boolean
  children: NavChild[]
}

export const navModules: NavModule[] = [
  {
    key: 'learning',
    label: '学习中心',
    icon: <ReadOutlined />,
    children: [
      { key: 'learning-overview', label: '数据概览', path: '/learning/overview', status: 'available' },
      { key: 'learning-home', label: '学习路径', path: '/learning', status: 'available' },
      { key: 'learning-admin', label: '内容运营', path: '/learning/admin', status: 'available', requiredRole: 'admin' },
      { key: 'learning-mcp', label: 'MCP 调试台', path: '/learning/mcp', status: 'available', requiredRole: 'admin' }
    ]
  },
  {
    key: 'workbench',
    label: '工作台',
    icon: <DashboardOutlined />,
    defaultVisible: false,
    children: [
      { key: 'workbench-todos', label: '我的待办', path: '/workbench/todos', status: 'available' },
      { key: 'workbench-recent', label: '最近访问', path: '/workbench/recent', status: 'available' }
    ]
  },
  {
    key: 'integration',
    label: '数据接入',
    icon: <DatabaseOutlined />,
    children: [
      { key: 'integration-sources', label: '数据源管理', path: '/integration/sources', status: 'available' },
      { key: 'integration-sync', label: '数据同步', path: '/integration/sync', status: 'available' },
      { key: 'integration-realtime', label: '实时采集', path: '/integration/realtime', status: 'comingSoon', description: '基于实时数据源的流式采集与增量接入。' },
      { key: 'integration-tasks', label: '接入任务', path: '/integration/tasks', status: 'available' }
    ]
  },
  {
    key: 'development',
    label: '数据开发',
    learnerLabel: '实训台',
    icon: <CodeOutlined />,
    children: [
      { key: 'development-sql', label: 'SQL开发', path: '/development/sql', status: 'available' },
      { key: 'development-spark', label: 'Spark开发', path: '/development/spark', status: 'available', description: '受管制品、执行恢复与混合流水线。' },
      { key: 'development-flink', label: 'Flink开发', path: '/development/flink', status: 'available', description: '流任务、checkpoint/savepoint 与失联对账。' },
      { key: 'development-workflows', label: '工作流', path: '/development/workflows', status: 'available' },
      { key: 'development-scheduling', label: '任务调度', path: '/development/scheduling', status: 'available' },
      { key: 'development-releases', label: '发布管理', path: '/development/releases', status: 'available' }
    ]
  },
  {
    key: 'modeling',
    label: '数据建模',
    icon: <PartitionOutlined />,
    children: [
      { key: 'modeling-domains', label: '主题域', path: '/modeling/domains', status: 'available' },
      { key: 'modeling-dimensions', label: '维度管理', path: '/modeling/dimensions', status: 'available' },
      { key: 'modeling-metrics', label: '指标管理', path: '/modeling/metrics', status: 'available', description: '定义业务指标的计算口径、归属与版本。' },
      { key: 'modeling-logical', label: '逻辑模型', path: '/modeling/logical-models', status: 'available', description: '面向业务的概念与逻辑模型设计。' },
      { key: 'modeling-physical', label: '物理模型', path: '/modeling/physical-models', status: 'available' },
      { key: 'modeling-lakehouse', label: '湖仓控制面', path: '/modeling/lakehouse', status: 'available', description: '管理 Iceberg 快照、分支、标签和维护作业。' },
      { key: 'modeling-reviews', label: '语义资产评审', path: '/modeling/reviews', status: 'available' },
      { key: 'modeling-dependencies', label: '语义依赖图', path: '/modeling/dependencies', status: 'available' }
    ]
  },
  {
    key: 'governance',
    label: '数据治理',
    icon: <SafetyCertificateOutlined />,
    children: [
      { key: 'governance-metadata', label: '元数据管理', path: '/governance/metadata', status: 'available' },
      { key: 'governance-standards', label: '数据标准', path: '/governance/standards', status: 'available' },
      { key: 'governance-quality', label: '数据质量', path: '/governance/quality', status: 'available' },
      { key: 'governance-quality-trend', label: '质量趋势', path: '/governance/quality-trend', status: 'available' },
      { key: 'governance-lineage', label: '数据血缘', path: '/governance/lineage', status: 'available' },
      { key: 'governance-field-lineage', label: '字段级血缘', path: '/governance/field-lineage', status: 'available' },
      { key: 'governance-classification', label: '分类分级', path: '/governance/classification', status: 'available' },
      { key: 'governance-issues', label: '治理问题', path: '/governance/issues', status: 'available' }
    ]
  },
  {
    key: 'assets',
    label: '数据资产',
    icon: <FolderOpenOutlined />,
    children: [
      { key: 'assets-catalog', label: '资产目录', path: '/assets/catalog', status: 'available' },
      { key: 'assets-tables', label: '数据表', path: '/assets/tables', status: 'available' },
      { key: 'assets-datasets', label: '数据集', path: '/assets/datasets', status: 'available', description: '管理面向分析场景的、可复用的数据集。' },
      { key: 'assets-metrics', label: '指标资产', path: '/assets/metrics', status: 'available' },
      { key: 'assets-tags', label: '标签资产', path: '/assets/tags', status: 'available' },
      { key: 'assets-apis', label: 'API 资产', path: '/assets/apis', status: 'available' },
      { key: 'assets-products', label: '数据产品', path: '/assets/products', status: 'available' },
      { key: 'assets-subscriptions', label: '数据订阅', path: '/assets/subscriptions', status: 'available' },
      { key: 'assets-authorization', label: '服务授权', path: '/assets/authorization', status: 'available' }
    ]
  },
  {
    key: 'analytics',
    label: '数据分析',
    icon: <BarChartOutlined />,
    children: [
      { key: 'analytics-query', label: '即席查询', path: '/analytics/query', status: 'available' },
      { key: 'analytics-dashboards', label: '数据看板', path: '/analytics/dashboards', status: 'available', description: '编排与发布面向业务的运营看板。' },
      { key: 'analytics-history', label: '查询历史', path: '/analytics/history', status: 'available' },
      { key: 'analytics-visualization', label: '可视化分析', path: '/analytics/visualization', status: 'comingSoon', description: '以拖拽方式编排图表，完成数据可视化探索。' }
    ]
  },
  {
    key: 'ai',
    label: '智能中心',
    learnerLabel: '智能助手',
    icon: <RobotOutlined />,
    children: [
      { key: 'ai-assistant', label: '数据助手', path: '/ai/assistant', status: 'available' },
      { key: 'ai-chatbi', label: '智能问数', path: '/ai/chatbi', status: 'available' },
      { key: 'ai-sql', label: 'SQL助手', path: '/ai/sql', status: 'comingSoon', description: '辅助编写、解释与优化 SQL。' },
      { key: 'ai-governance', label: '治理助手', path: '/ai/governance', status: 'comingSoon', description: '辅助完成数据标准、质量与血缘等治理任务。' },
      { key: 'ai-agents', label: 'Agent中心', path: '/ai/agents', status: 'comingSoon', description: '管理可编排的智能体与自动化流程。' }
    ]
  },
  {
    key: 'career',
    label: '我的求职',
    icon: <IdcardOutlined />,
    children: [
      { key: 'learning-portfolio', label: '求职证据', path: '/learning/portfolio', status: 'available' }
    ]
  },
  {
    key: 'operations',
    label: '运维中心',
    icon: <CloudServerOutlined />,
    children: [
      { key: 'operations-overview', label: '运行概览', path: '/operations/overview', status: 'available' },
      { key: 'operations-tasks', label: '任务监控', path: '/operations/tasks', status: 'available' },
      { key: 'operations-services', label: '服务监控', path: '/operations/services', status: 'available' },
      { key: 'operations-acceptance', label: '运行验收', path: '/operations/acceptance', status: 'available', description: '跟踪 R1-R5 验收、服务状态和数据源接入进度。' },
      { key: 'operations-alerts', label: '告警中心', path: '/operations/alerts', status: 'available' },
      { key: 'operations-query-audit', label: '查询审计', path: '/operations/query-audit', status: 'available', description: '查看语义查询成功率、P95 耗时和慢查询。' },
      { key: 'operations-audit', label: '审计日志', path: '/operations/audit', status: 'available', requiredRole: 'admin' },
      { key: 'operations-resources', label: '资源监控', path: '/operations/resources', status: 'comingSoon', description: '监控计算与存储资源的用量与水位。' },
      { key: 'operations-logs', label: '日志中心', path: '/operations/logs', status: 'comingSoon', description: '集中检索与查看平台运行日志。' }
    ]
  },
  {
    key: 'admin',
    label: '系统管理',
    icon: <SettingOutlined />,
    children: [
      { key: 'admin-users', label: '用户管理', path: '/admin/users', status: 'available', requiredRole: 'admin' },
      { key: 'admin-roles', label: '角色管理', path: '/admin/roles', status: 'available', requiredRole: 'admin' },
      { key: 'admin-permissions', label: '权限集合', path: '/admin/permissions', status: 'available', requiredRole: 'admin' },
      { key: 'admin-groups', label: '用户组', path: '/admin/groups', status: 'available', requiredRole: 'admin' },
      { key: 'admin-organizations', label: '组织管理', path: '/admin/organizations', status: 'available', requiredRole: 'admin' },
      { key: 'admin-workspaces', label: '项目空间', path: '/admin/workspaces', status: 'available', requiredRole: 'admin' },
      { key: 'admin-grants', label: '资源授权', path: '/admin/grants', status: 'available', requiredRole: 'admin' },
      { key: 'admin-settings', label: '系统配置', path: '/admin/settings', status: 'available', requiredRole: 'admin' },
      { key: 'system-preferences', label: '个人偏好', path: '/settings', status: 'available' },
      { key: 'system-navigation', label: '菜单配置', path: '/settings/navigation', status: 'available', requiredRole: 'admin' }
    ]
  }
]

export type UserRole = 'admin' | 'viewer'

/** 每个二级项在出厂配置里的归属模块，供菜单配置页展示「原属」。 */
export function defaultModuleOf(childKey: string): string | undefined {
  return navModules.find((module) => module.children.some((child) => child.key === childKey))?.key
}

export function allNavChildren(): NavChild[] {
  return navModules.flatMap((module) => module.children)
}

export function childByKey(key: string): NavChild | undefined {
  return allNavChildren().find((child) => child.key === key)
}

function byOrder(keys: string[] | undefined) {
  if (!keys || keys.length === 0) return () => 0
  const index = new Map(keys.map((key, position) => [key, position]))
  return (key: string) => index.get(key) ?? Number.MAX_SAFE_INTEGER
}

/**
 * 合并默认配置与用户覆盖项，得到当前角色真正可用的导航树。
 *
 * 规则：
 * 1. 二级项先按 itemModule 改归属，再按模块分组；改归属不会改动路由。
 * 2. 模块与二级项各自按配置顺序排序，未出现在顺序表里的保持原始相对次序。
 * 3. 过滤掉隐藏项、角色不可见项，以及没有任何可见二级项的模块。
 */
/** 构建期回滚开关：VITE_LEARNER_IA=false 时退回企业全量菜单。 */
const LEARNER_IA_ENABLED = import.meta.env.VITE_LEARNER_IA !== 'false'

/** 学习者视图保留的一级菜单；其余企业职能折叠进「平台管理」。 */
const LEARNER_MODULE_KEYS = new Set(['learning', 'development', 'assets', 'ai', 'career'])

export function resolveNavigation(role: UserRole, config: NavigationConfig): NavModule[] {
  const hiddenModules = new Set(config.hiddenModules)
  const hiddenItems = new Set(config.hiddenItems)

  const visibleChildren = (moduleKey: string) => {
    const overrides = config.itemModule
    const rank = byOrder(config.itemOrder[moduleKey])
    return allNavChildren()
      .filter((child) => {
        if (child.status !== 'available') return false
        if (child.requiredRole && child.requiredRole !== role) return false
        if (hiddenItems.has(child.key)) return false
        return (overrides[child.key] ?? defaultModuleOf(child.key)) === moduleKey
      })
      .sort((a, b) => rank(a.key) - rank(b.key))
  }

  const order = byOrder(config.moduleOrder)
  const modules = navModules
    .filter((module) => {
      if (hiddenModules.has(module.key)) return false
      if (module.defaultVisible === false && !config.visibleModules.includes(module.key)) return false
      return true
    })
    .slice()
    .sort((a, b) => order(a.key) - order(b.key))
    .map((module) => ({ ...module, children: visibleChildren(module.key) }))
    .filter((module) => module.children.length > 0)

  if (role === 'admin' || !LEARNER_IA_ENABLED) return modules

  // 学习者视图：只保留学习闭环相关的一级菜单，企业职能收进「平台管理」，
  // 避免十一个一级菜单把学习入口淹没。隐藏不等于禁用，直接 URL 仍可达。
  const learnerModules = modules
    .filter((module) => LEARNER_MODULE_KEYS.has(module.key))
    .map((module) => (module.learnerLabel ? { ...module, label: module.learnerLabel } : module))
  const enterpriseModules = modules.filter(
    (module) => !LEARNER_MODULE_KEYS.has(module.key) && module.key !== 'admin'
  )
  if (enterpriseModules.length === 0) return learnerModules

  const platform: NavModule = {
    key: 'platform-management',
    label: '平台管理',
    icon: <AppstoreOutlined />,
    children: [
      ...enterpriseModules.map((module) => ({
        key: `platform-${module.key}`,
        label: module.label,
        path: firstAvailablePath(module, role),
        status: 'available' as NavStatus
      })),
      { key: 'platform-preferences', label: '个人偏好', path: '/settings', status: 'available' as NavStatus }
    ]
  }
  return [...learnerModules, platform]
}

/** 按路径最长前缀匹配二级项（不依赖 URL 首段，兼容二级项改归属）。 */
export function findChildIn(modules: NavModule[], pathname: string): { module: NavModule; child: NavChild } | undefined {
  let best: { module: NavModule; child: NavChild } | undefined
  for (const module of modules) {
    for (const child of module.children) {
      if (pathname === child.path || pathname.startsWith(child.path + '/')) {
        if (!best || child.path.length > best.child.path.length) best = { module, child }
      }
    }
  }
  return best
}

export function findModuleIn(modules: NavModule[], pathname: string): NavModule | undefined {
  return findChildIn(modules, pathname)?.module
}

/** 当前模块下首个可访问的真实功能。 */
export function firstAvailablePath(module: NavModule, role?: UserRole): string {
  return module.children.find(
    (child) => child.status === 'available' && (!child.requiredRole || child.requiredRole === role)
  )?.path ?? '/learning'
}

export interface FlatNavItem {
  key: string
  label: string
  path: string
  moduleKey: string
  moduleLabel: string
  requiredRole?: 'admin'
}

/** 展平为一级/二级合并列表，供全局搜索与最近访问使用。 */
export function flattenNavigation(modules: NavModule[]): FlatNavItem[] {
  return modules.flatMap((module) =>
    module.children.map((child) => ({
      key: child.key,
      label: child.label,
      path: child.path,
      moduleKey: module.key,
      moduleLabel: module.label,
      requiredRole: child.requiredRole
    }))
  )
}

/* ------------------------------------------------------------------ *
 * 用户级覆盖配置
 * ------------------------------------------------------------------ */

export interface NavigationConfig {
  version: 1 | 2
  /** 显式隐藏的一级菜单。 */
  hiddenModules: string[]
  /** 显式打开的默认隐藏模块（例如把工作台放回顶栏）。 */
  visibleModules: string[]
  /** 隐藏的二级项。 */
  hiddenItems: string[]
  /** 二级项归属覆盖：childKey → moduleKey。 */
  itemModule: Record<string, string>
  /** 一级顺序；缺省按出厂顺序。 */
  moduleOrder: string[]
  /** 每个一级下的二级顺序；缺省按出厂顺序。 */
  itemOrder: Record<string, string[]>
}

export const NAVIGATION_CONFIG_KEY = 'aetheris-navigation-config'

export function defaultNavigationConfig(): NavigationConfig {
  return {
    version: 2,
    hiddenModules: [],
    visibleModules: [],
    hiddenItems: [],
    itemModule: {},
    moduleOrder: navModules.map((module) => module.key),
    itemOrder: Object.fromEntries(navModules.map((module) => [module.key, module.children.map((child) => child.key)]))
  }
}

/** 一级菜单当前是否展示（同时考虑出厂隐藏与用户覆盖）。 */
export function isModuleVisible(moduleKey: string, config: NavigationConfig): boolean {
  if (config.hiddenModules.includes(moduleKey)) return false
  const module = navModules.find((item) => item.key === moduleKey)
  if (!module) return false
  if (module.defaultVisible === false) return config.visibleModules.includes(moduleKey)
  return true
}

/** 二级项当前是否展示。 */
export function isItemVisible(childKey: string, config: NavigationConfig): boolean {
  return !config.hiddenItems.includes(childKey)
}

export function normalizeNavigationConfig(value: unknown): NavigationConfig {
  const base = defaultNavigationConfig()
  if (!value || typeof value !== 'object') return base
  const raw = value as Partial<NavigationConfig>

  const stringArray = (input: unknown): string[] =>
    Array.isArray(input) ? input.filter((item): item is string => typeof item === 'string') : []

  const moduleOrder = stringArray(raw.moduleOrder)
  const knownModules = new Set(navModules.map((module) => module.key))
  const merged = [
    ...moduleOrder.filter((key) => knownModules.has(key)),
    ...base.moduleOrder.filter((key) => !moduleOrder.includes(key))
  ]

  const rawVersion = typeof raw.version === 'number' ? raw.version : 1
  const itemOrder: Record<string, string[]> = {}
  for (const module of navModules) {
    const incoming = Array.isArray(raw.itemOrder?.[module.key]) ? raw.itemOrder![module.key] : []
    const known = new Set(module.children.map((child) => child.key))
    itemOrder[module.key] = [
      ...incoming.filter((key) => known.has(key)),
      ...module.children.map((child) => child.key).filter((key) => !incoming.includes(key))
    ]
  }

  // 版本 1 → 2：数据概览并入学习中心时做一次性迁移，把它补到首位；
  // 之后保存的版本 2 配置尊重用户自己的排序。
  if (rawVersion < 2) {
    itemOrder.learning = [
      'learning-overview',
      ...(itemOrder.learning ?? []).filter((key) => key !== 'learning-overview')
    ]
  }

  const itemModule: Record<string, string> = {}
  if (raw.itemModule && typeof raw.itemModule === 'object') {
    for (const [childKey, moduleKey] of Object.entries(raw.itemModule)) {
      if (typeof moduleKey === 'string' && knownModules.has(moduleKey)) itemModule[childKey] = moduleKey
    }
  }

  return {
    version: 2,
    hiddenModules: stringArray(raw.hiddenModules).filter((key) => knownModules.has(key)),
    visibleModules: stringArray(raw.visibleModules).filter((key) => knownModules.has(key)),
    hiddenItems: stringArray(raw.hiddenItems),
    itemModule,
    moduleOrder: merged,
    itemOrder
  }
}
