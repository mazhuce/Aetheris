import { createBrowserRouter, Navigate } from 'react-router-dom'
import BasicLayout from '@/layouts/BasicLayout'
import RequireAuth from '@/components/RequireAuth'
import LoginPage from '@/pages/login'
import Dashboard from '@/pages/dashboard'
import DataSourcePage from '@/pages/datasource'
import IngestionPage from '@/pages/ingestion'
import GovernancePage from '@/pages/governance'
import MetadataTablePage from '@/pages/metadata/tables'
import LineagePage from '@/pages/metadata/lineage'
import QualityRulePage from '@/pages/quality/rules'
import ChatPage from '@/pages/ai/chat'
import SystemConfigPage from '@/pages/system/config'
import UserPage from '@/pages/system/users'
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
      { path: 'metadata/lineage', element: <LineagePage /> },
      { path: 'quality/rules', element: <QualityRulePage /> },
      { path: 'ingestion/tasks', element: <IngestionPage /> },
      { path: 'governance/policies', element: <GovernancePage /> },
      { path: 'ai/chat', element: <ChatPage /> },
      { path: 'system/users', element: <UserPage /> },
      { path: 'system/config', element: <SystemConfigPage /> },
      { path: '*', element: <Placeholder /> }
    ]
  }
])
