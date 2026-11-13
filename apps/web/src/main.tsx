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
        {/* 开发模式下 StrictMode 会把首屏的数据请求跑两遍，同一句错误提示会
            并排叠两条红条，看起来像两个不同的问题。这里限制开发环境只留一条；
            生产没有双调用，保持多条以便同时暴露不同接口的失败。 */}
        <AntdApp message={{ maxCount: import.meta.env.DEV ? 1 : 3 }}>
          <QueryClientProvider client={queryClient}>
            <RouterProvider router={router} />
          </QueryClientProvider>
        </AntdApp>
      </NavigationProvider>
    </PreferencesProvider>
  </React.StrictMode>
)
