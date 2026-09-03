import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { App as AntdApp } from 'antd'
import { describe, expect, it } from 'vitest'
import { PreferencesProvider } from '@/contexts/PreferencesContext'
import { PREFERENCES_KEY } from '@/utils/preferences'
import SettingsPage from './index'

describe('SettingsPage', () => {
  it('switches and persists the light theme', async () => {
    render(
      <PreferencesProvider>
        <AntdApp>
          <SettingsPage />
        </AntdApp>
      </PreferencesProvider>
    )

    fireEvent.click(screen.getByText('浅色'))

    await waitFor(() => {
      expect(document.documentElement.dataset.theme).toBe('light')
      expect(localStorage.getItem(PREFERENCES_KEY)).toContain('"colorMode":"light"')
    })
  })

  it('offers the requested basic preference controls', () => {
    render(
      <PreferencesProvider>
        <AntdApp>
          <SettingsPage />
        </AntdApp>
      </PreferencesProvider>
    )

    expect(screen.getByText('界面主题')).toBeInTheDocument()
    expect(screen.getByText('默认首页')).toBeInTheDocument()
    expect(screen.getByRole('switch', { name: '减少动态效果' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /恢复默认设置/ })).toBeInTheDocument()
  })
})
