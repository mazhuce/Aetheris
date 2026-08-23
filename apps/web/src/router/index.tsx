import { createBrowserRouter, Navigate } from 'react-router-dom'
import BasicLayout from '@/layouts/BasicLayout'
import Dashboard from '@/pages/dashboard'
import DataSourcePage from '@/pages/datasource'
import QualityRulePage from '@/pages/quality/rules'
import SystemConfigPage from '@/pages/system/config'
import UserPage from '@/pages/system/users'
import Placeholder from '@/pages/placeholder'

export const router = createBrowserRouter([
  {
    path: '/',
    element: <BasicLayout />,
    children: [
      { index: true, element: <Navigate to="/dashboard" replace /> },
      { path: 'dashboard', element: <Dashboard /> },
      { path: 'metadata/datasource', element: <DataSourcePage /> },
      { path: 'quality/rules', element: <QualityRulePage /> },
      { path: 'system/users', element: <UserPage /> },
      { path: 'system/config', element: <SystemConfigPage /> },
      { path: '*', element: <Placeholder /> }
    ]
  }
])
