import { createBrowserRouter, Navigate } from 'react-router-dom'
import BasicLayout from '@/layouts/BasicLayout'
import RequireAuth from '@/components/RequireAuth'
import RequireRole from '@/components/RequireRole'
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
import Placeholder from '@/pages/placeholder'

export const router = createBrowserRouter([
  { path: '/login', element: <LoginPage /> },
  {
    path: '/',
    element: (
      <RequireAuth>
        <BasicLayout />
      </RequireAuth>
    ),
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'metadata/datasource', element: <DataSourcePage /> },
      { path: 'metadata/tables', element: <MetadataTablePage /> },
      { path: 'metadata/warehouse', element: <WarehouseMapPage /> },
      { path: 'metadata/lineage', element: <LineagePage /> },
      { path: 'quality/rules', element: <QualityRulePage /> },
      { path: 'ingestion/tasks', element: <IngestionPage /> },
      { path: 'workflow', element: <WorkflowPage /> },
      { path: 'governance/policies', element: <GovernancePage /> },
      { path: 'ai/chat', element: <ChatPage /> },
      { path: 'account', element: <AccountPage /> },
      {
        path: 'system/users',
        element: (
          <RequireRole role="admin">
            <UserPage />
          </RequireRole>
        )
      },
      { path: 'system/config', element: <SystemConfigPage /> },
      {
        path: 'system/audit',
        element: (
          <RequireRole role="admin">
            <AuditLogPage />
          </RequireRole>
        )
      },
      { path: '*', element: <Placeholder /> }
    ]
  }
])
