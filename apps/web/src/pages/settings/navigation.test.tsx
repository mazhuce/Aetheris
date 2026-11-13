import { render, screen, fireEvent, act } from '@testing-library/react'
import { App } from 'antd'
import { MemoryRouter } from 'react-router-dom'
import NavigationConfigPage from './navigation'
import { NavigationProvider } from '@/contexts/NavigationContext'
import { NAVIGATION_CONFIG_KEY } from '@/constants/navigation'

class ResizeObserverStub {
  observe() {}
  unobserve() {}
  disconnect() {}
}

beforeAll(() => {
  Object.defineProperty(window, 'ResizeObserver', { writable: true, value: ResizeObserverStub })
})

const renderPage = () =>
  render(
    <NavigationProvider>
      <App>
        <MemoryRouter>
          <NavigationConfigPage />
        </MemoryRouter>
      </App>
    </NavigationProvider>
  )

describe('菜单配置页', () => {
  it('列出一级与二级菜单，工作台默认关闭', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '菜单配置' })).toBeInTheDocument()
    expect(screen.getByLabelText('工作台是否展示')).toHaveAttribute('aria-checked', 'false')
    expect(screen.getByLabelText('学习中心是否展示')).toHaveAttribute('aria-checked', 'true')
  })

  it('关闭一级菜单后开关状态与本地配置同步', () => {
    renderPage()
    const toggle = screen.getByLabelText('数据分析是否展示')

    act(() => {
      fireEvent.click(toggle)
    })

    expect(screen.getByLabelText('数据分析是否展示')).toHaveAttribute('aria-checked', 'false')
    expect(localStorage.getItem(NAVIGATION_CONFIG_KEY)).toContain('analytics')
  })

  it('二级菜单行提供了归属选择与展示开关', () => {
    renderPage()
    expect(screen.getByLabelText('指标资产是否展示')).toBeInTheDocument()
    // antd Select 会把 aria-label 同时挂到内层 input 与组合框上，这里只校验渲染存在
    expect(screen.getAllByLabelText('指标资产归属').length).toBeGreaterThan(0)
  })
})
