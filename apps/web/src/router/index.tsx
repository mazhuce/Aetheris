import { lazy, Suspense, type ReactNode } from 'react'
import { createBrowserRouter, Navigate } from 'react-router-dom'
import BasicLayout from '@/layouts/BasicLayout'
import RequireAuth from '@/components/RequireAuth'
import RequireRole from '@/components/RequireRole'
import { firstAvailablePath, navModules } from '@/constants/navigation'
import { getCurrentRole } from '@/utils/access'
import { usePreferences } from '@/contexts/PreferencesContext'

const LoginPage = lazy(() => import('@/pages/login'))
const Dashboard = lazy(() => import('@/pages/dashboard'))
const TodosPage = lazy(() => import('@/pages/workbench/todos'))
const DataSourcePage = lazy(() => import('@/pages/datasource'))
const IngestionPage = lazy(() => import('@/pages/ingestion'))
const GovernancePage = lazy(() => import('@/pages/governance'))
const MetadataTablePage = lazy(() => import('@/pages/metadata/tables'))
const WarehouseMapPage = lazy(() => import('@/pages/metadata/warehouse'))
const LineagePage = lazy(() => import('@/pages/metadata/lineage'))
const WorkflowPage = lazy(() => import('@/pages/workflow'))
const QualityRulePage = lazy(() => import('@/pages/quality/rules'))
const QualityTrendPage = lazy(() => import('@/pages/quality/trend'))
const ChatPage = lazy(() => import('@/pages/ai/chat'))
const ChatBiPage = lazy(() => import('@/pages/ai/chatbi'))
const SystemConfigPage = lazy(() => import('@/pages/system/config'))
const UserPage = lazy(() => import('@/pages/system/users'))
const RolePage = lazy(() => import('@/pages/system/roles'))
const PermissionPage = lazy(() => import('@/pages/system/permissions'))
const GroupPage = lazy(() => import('@/pages/system/groups'))
const OrganizationPage = lazy(() => import('@/pages/system/organizations'))
const WorkspacePage = lazy(() => import('@/pages/system/workspaces'))
const GrantPage = lazy(() => import('@/pages/system/grants'))
const AuditLogPage = lazy(() => import('@/pages/system/audit'))
const AccountPage = lazy(() => import('@/pages/account'))
const SettingsPage = lazy(() => import('@/pages/settings'))
const AnalyticsDatasetPage = lazy(() => import('@/pages/analytics/datasets'))
const AdhocQueryPage = lazy(() => import('@/pages/analytics/query'))
const TaskMonitorPage = lazy(() => import('@/pages/operations/tasks'))
const AlertCenterPage = lazy(() => import('@/pages/operations/alerts'))
const ServiceMonitorPage = lazy(() => import('@/pages/operations/services'))
const QueryHistoryPage = lazy(() => import('@/pages/analytics/history'))
const QueryAuditPage = lazy(() => import('@/pages/analytics/query-audit'))
const OperationsOverviewPage = lazy(() => import('@/pages/workbench/operations'))
const TableBrowserPage = lazy(() => import('@/pages/assets/tables'))
const DataSyncPage = lazy(() => import('@/pages/integration/sync'))
const SqlDevelopmentPage = lazy(() => import('@/pages/development/sql'))
const SparkDevelopmentPage = lazy(() => import('@/pages/development/spark'))
const FlinkDevelopmentPage = lazy(() => import('@/pages/development/flink'))
const AcceptanceProgressPage = lazy(() => import('@/pages/development/acceptance'))
const AssetCatalogPage = lazy(() => import('@/pages/assets/catalog'))
const DomainPage = lazy(() => import('@/pages/modeling/domains'))
const GovernanceIssuesPage = lazy(() => import('@/pages/governance/issues'))
const RecentVisitsPage = lazy(() => import('@/pages/workbench/recent'))
const DimensionPage = lazy(() => import('@/pages/modeling/dimensions'))
const MetricAssetsPage = lazy(() => import('@/pages/assets/metrics'))
const TagAssetsPage = lazy(() => import('@/pages/assets/tags'))
const DashboardListPage = lazy(() => import('@/pages/analytics/dashboards'))
const DashboardEditorPage = lazy(() => import('@/pages/analytics/dashboards/editor'))
const DashboardPreviewPage = lazy(() => import('@/pages/analytics/dashboards/stage').then((module) => ({ default: module.DashboardPreviewPage })))
const PublishedDashboardPage = lazy(() => import('@/pages/analytics/dashboards/stage').then((module) => ({ default: module.PublishedDashboardPage })))
const MetricManagePage = lazy(() => import('@/pages/modeling/metrics'))
const LogicalModelPage = lazy(() => import('@/pages/modeling/logical-models'))
const SemanticReviewPage = lazy(() => import('@/pages/modeling/reviews'))
const FieldLineagePage = lazy(() => import('@/pages/governance/field-lineage'))
const SemanticDependencyPage = lazy(() => import('@/pages/governance/dependencies'))
const ApiAssetPage = lazy(() => import('@/pages/assets/apis'))
const DataProductPage = lazy(() => import('@/pages/assets/products'))
const DataStandardPage = lazy(() => import('@/pages/governance/standards'))
const ReleasePage = lazy(() => import('@/pages/development/releases'))
const WorkflowGraphPage = lazy(() => import('@/pages/development/workflows'))
const SubscriptionPage = lazy(() => import('@/pages/services/subscriptions'))
const ServiceAuthorizationPage = lazy(() => import('@/pages/services/authorization'))

function Deferred({ children }: { children: ReactNode }) {
  return <Suspense fallback={<div className="route-loading">页面加载中…</div>}>{children}</Suspense>
}

/** 一级模块根路径 → 默认二级页（运行时按当前角色解析，避免加载期固化角色）。 */
function ModuleRedirect({ moduleKey }: { moduleKey: string }) {
  const module = navModules.find((item) => item.key === moduleKey)
  if (!module) return <Navigate to="/workbench" replace />
  return <Navigate to={firstAvailablePath(module, getCurrentRole())} replace />
}

function HomeRedirect() {
  const { homePath } = usePreferences()
  return <Navigate to={homePath} replace />
}

export const router = createBrowserRouter([
  { path: '/login', element: <Deferred><LoginPage /></Deferred> },
  {
    path: '/analytics/dashboards/:id/preview',
    element: <RequireAuth><Deferred><DashboardPreviewPage /></Deferred></RequireAuth>
  },
  {
    path: '/d/:slug',
    element: <RequireAuth><Deferred><PublishedDashboardPage /></Deferred></RequireAuth>
  },
  {
    path: '/',
    element: (
      <RequireAuth>
        <Deferred><BasicLayout /></Deferred>
      </RequireAuth>
    ),
    children: [
      { index: true, element: <HomeRedirect /> },

      // 工作台
      { path: 'workbench', element: <Dashboard /> },
      { path: 'workbench/todos', element: <TodosPage /> },

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
      { path: 'governance/quality-trend', element: <QualityTrendPage /> },
      { path: 'governance/lineage', element: <LineagePage /> },
      { path: 'governance/field-lineage', element: <FieldLineagePage /> },
      { path: 'governance/dependencies', element: <SemanticDependencyPage /> },
      { path: 'governance/classification', element: <GovernancePage /> },

      // 智能中心
      { path: 'ai/assistant', element: <ChatPage /> },
      { path: 'ai/chatbi', element: <ChatBiPage /> },

      // 数据资产与分析
      { path: 'assets/datasets', element: <AnalyticsDatasetPage /> },
      { path: 'analytics/query', element: <AdhocQueryPage /> },
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
      { path: 'operations/tasks', element: <TaskMonitorPage /> },
      { path: 'operations/alerts', element: <AlertCenterPage /> },
      { path: 'operations/services', element: <ServiceMonitorPage /> },
      { path: 'analytics/history', element: <QueryHistoryPage /> },
      { path: 'analytics/query-audit', element: <QueryAuditPage /> },
      { path: 'assets/tables', element: <TableBrowserPage /> },
      { path: 'integration/sync', element: <DataSyncPage /> },
      { path: 'development/sql', element: <SqlDevelopmentPage /> },
      { path: 'development/spark', element: <SparkDevelopmentPage /> },
      { path: 'development/flink', element: <FlinkDevelopmentPage /> },
      { path: 'development/acceptance', element: <AcceptanceProgressPage /> },
      { path: 'assets/catalog', element: <AssetCatalogPage /> },
      { path: 'modeling/domains', element: <DomainPage /> },
      { path: 'governance/issues', element: <GovernanceIssuesPage /> },
      { path: 'workbench/recent', element: <RecentVisitsPage /> },
      { path: 'modeling/dimensions', element: <DimensionPage /> },
      { path: 'assets/metrics', element: <MetricAssetsPage /> },
      { path: 'assets/tags', element: <TagAssetsPage /> },
      { path: 'modeling/metrics', element: <MetricManagePage /> },
      { path: 'modeling/logical-models', element: <LogicalModelPage /> },
      { path: 'modeling/reviews', element: <SemanticReviewPage /> },
      { path: 'assets/apis', element: <ApiAssetPage /> },
      { path: 'assets/products', element: <DataProductPage /> },
      { path: 'governance/standards', element: <DataStandardPage /> },
      { path: 'development/releases', element: <ReleasePage /> },
      { path: 'development/workflows', element: <WorkflowGraphPage /> },
      { path: 'services/subscriptions', element: <SubscriptionPage /> },
      { path: 'services/authorization', element: <ServiceAuthorizationPage /> },
      { path: 'workbench/operations', element: <OperationsOverviewPage /> },
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
      {
        path: 'admin/roles',
        element: (
          <RequireRole role="admin">
            <RolePage />
          </RequireRole>
        )
      },
      {
        path: 'admin/permissions',
        element: (
          <RequireRole role="admin">
            <PermissionPage />
          </RequireRole>
        )
      },
      {
        path: 'admin/groups',
        element: (
          <RequireRole role="admin">
            <GroupPage />
          </RequireRole>
        )
      },
      {
        path: 'admin/organizations',
        element: (
          <RequireRole role="admin">
            <OrganizationPage />
          </RequireRole>
        )
      },
      {
        path: 'admin/workspaces',
        element: (
          <RequireRole role="admin">
            <WorkspacePage />
          </RequireRole>
        )
      },
      {
        path: 'admin/grants',
        element: (
          <RequireRole role="admin">
            <GrantPage />
          </RequireRole>
        )
      },
      { path: 'admin/settings', element: <SystemConfigPage /> },

      // 系统与账户偏好（头部下拉进入，不进导航）
      { path: 'settings', element: <SettingsPage /> },
      { path: 'account', element: <AccountPage /> },

      // 一级模块根路径重定向；未交付模块不注册入口。
      ...navModules
        .filter((module) => module.children.some((child) => child.status === 'available'))
        .filter((module) => !module.children.some((child) => child.path === `/${module.key}` && child.status === 'available'))
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
