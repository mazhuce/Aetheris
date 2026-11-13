import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntdApp } from 'antd'
import { RouterProvider } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import { queryClient } from './auth/queryClient'
import { startSessionSynchronization } from './auth/sessionSynchronization'
import { router } from './router'
import { PreferencesProvider } from './contexts/PreferencesContext'
import { NavigationProvider } from './contexts/NavigationContext'
import { applyPreferences, readPreferences } from './utils/preferences'
import '@fontsource-variable/dm-sans/wght.css'
import '@fontsource-variable/host-grotesk/wght.css'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'
import './styles/global.css'

const stopSessionSynchronization = startSessionSynchronization()
if (import.meta.hot) import.meta.hot.dispose(stopSessionSynchronization)

// 在首屏渲染前恢复主题，避免已选择浅色时短暂闪现深色界面。
applyPreferences(readPreferences())

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <PreferencesProvider>
      <NavigationProvider>
        <AntdApp>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </AntdApp>
      </NavigationProvider>
    </PreferencesProvider>
  </React.StrictMode>
)
