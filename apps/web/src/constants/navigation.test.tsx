import { availableNavModules, flattenNavItems } from './navigation'

describe('navigation visibility', () => {
  it('只把已经交付的功能放入导航和全局搜索', () => {
    const viewerModules = availableNavModules('viewer')
    const children = viewerModules.flatMap((module) => module.children)

    expect(children.length).toBeGreaterThan(0)
    expect(children.every((child) => child.status === 'available')).toBe(true)
    expect(flattenNavItems().some((item) => item.path === '/services/apis')).toBe(false)
  })

  it('按角色隐藏管理入口及没有可用页面的模块', () => {
    const viewerModules = availableNavModules('viewer')
    const adminModules = availableNavModules('admin')

    expect(viewerModules.some((module) => module.key === 'operations')).toBe(true)
    expect(viewerModules.flatMap((module) => module.children).some((child) => child.path === '/operations/audit')).toBe(false)
    expect(viewerModules.flatMap((module) => module.children).some((child) => child.path === '/admin/users')).toBe(false)
    expect(adminModules.flatMap((module) => module.children).some((child) => child.path === '/admin/users')).toBe(true)
    // 数据服务模块的订阅/授权页对 viewer 只读开放，模块应可见；
    // 其中仍是占位的 API 管理/开发/监控不应出现在导航里。
    expect(viewerModules.some((module) => module.key === 'services')).toBe(true)
    expect(viewerModules.flatMap((module) => module.children).some((child) => child.path === '/services/apis')).toBe(false)
    expect(viewerModules.flatMap((module) => module.children).some((child) => child.path === '/services/monitoring')).toBe(false)
  })
})
