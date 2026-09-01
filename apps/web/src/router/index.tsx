import { createBrowserRouter, Navigate } from 'react-router-dom'
import BasicLayout from '@/layouts/BasicLayout'
import RequireAuth from '@/components/RequireAuth'
import RequireRole from '@/components/RequireRole'
import { firstAvailablePath, navModules } from '@/constants/navigation'
import { getCurrentRole } from '@/utils/access'
import LoginPage from '@/pages/login'
import Dashboard from '@/pages/dashboard'
import DataSourcePage from '@/pages/datasource'
import IngestionPage from '@/pages/ingestion'
import GovernancePage from '@/pages/governance'
import MetadataTablePage from '@/pages/metadata/tables'
import WarehouseMapPage from '@/pages/metadata/warehouse'
import LineagePage from '@/pages/metadata/lineage'
import WorkflowPage from '@/pages/workflow'
import QualityRulePage from '@/pages/quality/rules'
import ChatPage from '@/pages/ai/chat'
import SystemConfigPage from '@/pages/system/config'
import UserPage from '@/pages/system/users'
import AuditLogPage from '@/pages/system/audit'
import AccountPage from '@/pages/account'
import AnalyticsDatasetPage from '@/pages/analytics/datasets'
import DashboardListPage from '@/pages/analytics/dashboards'
import DashboardEditorPage from '@/pages/analytics/dashboards/editor'
import { DashboardPreviewPage, PublishedDashboardPage } from '@/pages/analytics/dashboards/stage'
import Placeholder from '@/pages/placeholder'

/** 占位路由：由导航配置统一生成（status === 'comingSoon'）。 */
const placeholderRoutes = navModules.flatMap((module) =>
  module.children
    .filter((child) => child.status === 'comingSoon')
    .map((child) => ({ path: child.path.slice(1), element: <Placeholder /> }))
)

/** 一级模块根路径 → 默认二级页（运行时按当前角色解析，避免加载期固化角色）。 */
function ModuleRedirect({ moduleKey }: { moduleKey: string }) {
  const module = navModules.find((item) => item.key === moduleKey)
  if (!module) return <Navigate to="/workbench" replace />
  return <Navigate to={firstAvailablePath(module, getCurrentRole())} replace />
}

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/analytics/dashboards/:id/preview',
    element: <RequireAuth><DashboardPreviewPage /></RequireAuth>
  },
  {
    path: '/d/:slug',
    element: <RequireAuth><PublishedDashboardPage /></RequireAuth>
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <BasicLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/workbench" replace /> },

      // 工作台
      { path: 'workbench', element: <Dashboard /> },

      // 数据接入
      { path: 'integration/sources', element: <DataSourcePage /> },
      { path: 'integration/tasks', element: <IngestionPage /> },

      // 数据开发
      { path: 'development/scheduling', element: <WorkflowPage /> },

      // 数据建模
      { path: 'modeling/physical-models', element: <WarehouseMapPage /> },

      // 数据治理
      { path: 'governance/metadata', element: <MetadataTablePage /> },
      { path: 'governance/quality', element: <QualityRulePage /> },
      { path: 'governance/lineage', element: <LineagePage /> },
      { path: 'governance/classification', element: <GovernancePage /> },

      // 智能中心
      { path: 'ai/assistant', element: <ChatPage /> },

      // 数据资产与分析
      { path: 'assets/datasets', element: <AnalyticsDatasetPage /> },
      { path: 'analytics/dashboards', element: <DashboardListPage /> },
      {
        path: 'analytics/dashboards/:id/edit',
        element: (
          <RequireRole role="admin">
            <DashboardEditorPage />
          </RequireRole>
        )
      },

      // 运维中心
      {
        path: 'operations/audit',
        element: (
          <RequireRole role="admin">
            <AuditLogPage />
          </RequireRole>
        )
      },

      // 系统管理
      {
        path: 'admin/users',
        element: (
          <RequireRole role="admin">
            <UserPage />
          </RequireRole>
        )
      },
      { path: 'admin/settings', element: <SystemConfigPage /> },

      // 账户设置（头部下拉进入，不进导航）
      { path: 'account', element: <AccountPage /> },

      // 占位页（生成）
      ...placeholderRoutes,

      // 一级模块根路径重定向（排除根路径已等于某个二级页的模块，如工作台）
      ...navModules
        .filter((module) => !module.children.some((child) => child.path === `/${module.key}`))
        .map((module) => ({ path: module.key, element: <ModuleRedirect moduleKey={module.key} /> })),

      // 旧路由兼容重定向
      { path: 'dashboard', element: <Navigate to="/workbench" replace /> },
      { path: 'metadata/datasource', element: <Navigate to="/integration/sources" replace /> },
      { path: 'metadata/tables', element: <Navigate to="/governance/metadata" replace /> },
      { path: 'metadata/warehouse', element: <Navigate to="/modeling/physical-models" replace /> },
      { path: 'metadata/lineage', element: <Navigate to="/governance/lineage" replace /> },
      { path: 'quality/rules', element: <Navigate to="/governance/quality" replace /> },
      { path: 'ingestion/tasks', element: <Navigate to="/integration/tasks" replace /> },
      { path: 'workflow', element: <Navigate to="/development/scheduling" replace /> },
      { path: 'governance/policies', element: <Navigate to="/governance/classification" replace /> },
      { path: 'ai/chat', element: <Navigate to="/ai/assistant" replace /> },
      { path: 'system/users', element: <Navigate to="/admin/users" replace /> },
      { path: 'system/config', element: <Navigate to="/admin/settings" replace /> },
      { path: 'system/audit', element: <Navigate to="/operations/audit" replace /> },

      // 未知路径兜底
      { path: '*', element: <Navigate to="/workbench" replace /> }
    ]
  }
])
