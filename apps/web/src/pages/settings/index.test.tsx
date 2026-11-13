import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import type { ReactNode } from 'react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it } from 'vitest'
import { NavigationProvider } from '@/contexts/NavigationContext'
import { PreferencesProvider } from '@/contexts/PreferencesContext'
import { PREFERENCES_KEY } from '@/utils/preferences'
import SettingsPage from './index'

const renderPage = (children: ReactNode = <SettingsPage />) =>
  render(
    <MemoryRouter>
      <PreferencesProvider>
        <NavigationProvider>
          <AntdApp>{children}</AntdApp>
        </NavigationProvider>
      </PreferencesProvider>
    </MemoryRouter>
  )

describe('SettingsPage', () => {
  it('switches and persists the light theme', async () => {
    renderPage()

    fireEvent.click(screen.getByText('浅色'))

    await waitFor(() => {
      expect(document.documentElement.dataset.theme).toBe('light')
      expect(localStorage.getItem(PREFERENCES_KEY)).toContain('"colorMode":"light"')
    })
  })

  it('offers the requested basic preference controls', () => {
    renderPage()

    expect(screen.getByText('界面主题')).toBeInTheDocument()
    expect(screen.getByText('默认首页')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: '减少动态效果' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /恢复默认设置/ })).toBeInTheDocument()
  })

  it('surfaces the number of visible menus on the navigation config entry', () => {
    renderPage()

    expect(screen.getByText('菜单配置')).toBeInTheDocument()
    expect(screen.getByText(/自定义顶栏与二级菜单/)).toBeInTheDocument()
    expect(screen.getByText(/当前展示 \d+ 个一级菜单、\d+ 个二级菜单/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /进入菜单配置/ })).toBeInTheDocument()
  })
})
