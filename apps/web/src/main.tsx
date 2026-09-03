import React from 'react'
import ReactDOM from 'react-dom/client'
import { App as AntdApp } from 'antd'
import { RouterProvider } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { router } from './router'
import { PreferencesProvider } from './contexts/PreferencesContext'
import { applyPreferences, readPreferences } from './utils/preferences'
import '@fontsource-variable/dm-sans/wght.css'
import '@fontsource-variable/host-grotesk/wght.css'
import 'react-grid-layout/css/styles.css'
import 'react-resizable/css/styles.css'
import './styles/global.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 10_000,
      retry: 1,
      refetchOnWindowFocus: false
    }
  }
})

// 在首屏渲染前恢复主题，避免已选择浅色时短暂闪现深色界面。
applyPreferences(readPreferences())

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <PreferencesProvider>
      <AntdApp>
        <QueryClientProvider client={queryClient}>
          <RouterProvider router={router} />
        </QueryClientProvider>
      </AntdApp>
    </PreferencesProvider>
  </React.StrictMode>
)
