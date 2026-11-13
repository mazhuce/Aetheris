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
      <div data-testid="analytics">{labelsOf('analytics')}</div>
      <div data-testid="modeling">{labelsOf('modeling')}</div>
      <button onClick={() => patchConfig({ hiddenModules: [...config.hiddenModules, 'analytics'] })}>hide-analytics</button>
      <button onClick={() => patchConfig({ itemModule: { 'assets-metrics': 'modeling' } })}>move-metric</button>
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
  it('默认隐藏工作台，并把默认菜单渲染出来', () => {
    renderProbe()
    expect(screen.getByTestId('modules').textContent).not.toContain('工作台')
    expect(screen.getByTestId('modules').textContent).toContain('学习中心')
  })

  it('切换一级菜单后导航立即重算', () => {
    renderProbe()
    expect(screen.getByTestId('analytics').textContent).not.toBe('none')

    act(() => {
      fireEvent.click(screen.getByText('hide-analytics'))
    })

    expect(screen.getByTestId('analytics').textContent).toBe('none')
    expect(screen.getByTestId('modules').textContent).not.toContain('数据分析')
  })

  it('改归属后二级项出现在新的一级菜单下', () => {
    renderProbe()
    expect(screen.getByTestId('modeling').textContent ?? '').not.toContain('指标资产')

    act(() => {
      fireEvent.click(screen.getByText('move-metric'))
    })

    expect(screen.getByTestId('modeling').textContent).toContain('指标资产')
  })

  it('可以把默认隐藏的工作台重新打开', () => {
    renderProbe()
    act(() => {
      fireEvent.click(screen.getByText('show-workbench'))
    })
    expect(screen.getByTestId('modules').textContent).toContain('工作台')
  })

  it('配置写入本地存储，恢复默认后清空', () => {
    renderProbe()
    act(() => {
      fireEvent.click(screen.getByText('hide-analytics'))
    })
    expect(localStorage.getItem(NAVIGATION_CONFIG_KEY)).toContain('analytics')

    act(() => {
      fireEvent.click(screen.getByText('reset'))
    })
    expect(screen.getByTestId('analytics').textContent).not.toBe('none')
  })
})
