import { render, screen, fireEvent, act } from '@testing-library/react'
import { NavigationProvider, useNavigation, useNavigationConfig } from './NavigationContext'
import { NAVIGATION_CONFIG_KEY } from '@/constants/navigation'

function Probe() {
  const { modules } = useNavigation()
  const { config, patchConfig, resetConfig } = useNavigationConfig()
  const labelsOf = (key: string) =>
    modules.find((module) => module.key === key)?.children.map((child) => child.label).join('|') ?? 'none'

  return (
    <div>
      <div data-testid="modules">{modules.map((module) => module.label).join('|')}</div>
      <div data-testid="assets">{labelsOf('assets')}</div>
      <div data-testid="development">{labelsOf('development')}</div>
      <div data-testid="platform">{labelsOf('platform-management')}</div>
      <button onClick={() => patchConfig({ hiddenModules: [...config.hiddenModules, 'assets'] })}>hide-assets</button>
      <button onClick={() => patchConfig({ itemModule: { 'assets-metrics': 'development' } })}>move-metric</button>
      <button onClick={() => patchConfig({ visibleModules: ['workbench'] })}>show-workbench</button>
      <button onClick={() => resetConfig()}>reset</button>
    </div>
  )
}

const renderProbe = () =>
  render(
    <NavigationProvider>
      <Probe />
    </NavigationProvider>
  )

describe('NavigationProvider', () => {
  it('默认隐藏工作台，并把学习者菜单渲染出来', () => {
    renderProbe()
    expect(screen.getByTestId('modules').textContent).not.toContain('工作台')
    expect(screen.getByTestId('modules').textContent).toContain('学习中心')
    expect(screen.getByTestId('modules').textContent).toContain('实训台')
  })

  it('切换一级菜单后导航立即重算', () => {
    renderProbe()
    expect(screen.getByTestId('assets').textContent).not.toBe('none')

    act(() => {
      fireEvent.click(screen.getByText('hide-assets'))
    })

    expect(screen.getByTestId('assets').textContent).toBe('none')
    expect(screen.getByTestId('modules').textContent).not.toContain('数据资产')
  })

  it('改归属后二级项出现在新的一级菜单下', () => {
    renderProbe()
    expect(screen.getByTestId('development').textContent ?? '').not.toContain('指标资产')

    act(() => {
      fireEvent.click(screen.getByText('move-metric'))
    })

    expect(screen.getByTestId('development').textContent).toContain('指标资产')
  })

  it('开启默认隐藏的工作台后收进平台管理', () => {
    renderProbe()
    expect(screen.getByTestId('platform').textContent).not.toContain('工作台')

    act(() => {
      fireEvent.click(screen.getByText('show-workbench'))
    })

    expect(screen.getByTestId('platform').textContent).toContain('工作台')
    expect(screen.getByTestId('modules').textContent).not.toContain('工作台|')
  })

  it('配置写入本地存储，恢复默认后清空', () => {
    renderProbe()
    act(() => {
      fireEvent.click(screen.getByText('hide-assets'))
    })
    expect(localStorage.getItem(NAVIGATION_CONFIG_KEY)).toContain('assets')

    act(() => {
      fireEvent.click(screen.getByText('reset'))
    })
    expect(screen.getByTestId('assets').textContent).not.toBe('none')
  })
})