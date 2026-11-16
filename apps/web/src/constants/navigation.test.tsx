import {
  allNavChildren,
  defaultNavigationConfig,
  defaultModuleOf,
  flattenNavigation,
  normalizeNavigationConfig,
  navModules,
  resolveNavigation
} from './navigation'

const keysOf = (modules: ReturnType<typeof resolveNavigation>) =>
  modules.flatMap((module) => module.children.map((child) => child.key))

describe('navigation defaults', () => {
  it('默认不占用顶栏的模块仍然保留二级项，可在菜单配置里重新打开', () => {
    const modules = resolveNavigation('viewer', defaultNavigationConfig())
    expect(modules.some((module) => module.key === 'workbench')).toBe(false)

    const workbench = navModules.find((module) => module.key === 'workbench')
    expect(workbench?.defaultVisible).toBe(false)
    expect(workbench?.children.map((child) => child.path)).toContain('/workbench')
  })

  it('二级项 key 与路径全局唯一，避免菜单配置改归属时互相覆盖', () => {
    const children = allNavChildren()
    const keys = children.map((child) => child.key)
    const paths = children.map((child) => child.path)
    expect(new Set(keys).size).toBe(keys.length)
    expect(new Set(paths).size).toBe(paths.length)
  })

  it('只呈现已交付能力，规划项不进导航', () => {
    const modules = resolveNavigation('admin', defaultNavigationConfig())
    const children = modules.flatMap((module) => module.children)
    expect(children.length).toBeGreaterThan(0)
    expect(children.every((child) => child.status === 'available')).toBe(true)
    expect(children.some((child) => child.path === '/services/apis')).toBe(false)
  })
})

describe('learner information architecture', () => {
  it('viewer 只看到学习闭环入口，企业菜单折叠进平台管理', () => {
    const modules = resolveNavigation('viewer', defaultNavigationConfig())

    expect(modules[0].key).toBe('learning')
    expect(modules.slice(0, -1).every((module) => ['learning', 'development', 'assets', 'ai'].includes(module.key))).toBe(true)
    expect(modules.length).toBeLessThanOrEqual(6)

    const platform = modules.find((module) => module.key === 'platform-management')
    expect(platform).toBeDefined()
    const labels = platform?.children.map((child) => child.label) ?? []
    expect(labels).toEqual(expect.arrayContaining(['数据接入', '数据建模', '数据治理', '数据分析', '运维中心', '个人偏好']))
  })

  it('viewer 不再看到运营工具，admin 仍可见', () => {
    const viewer = resolveNavigation('viewer', defaultNavigationConfig())
    const admin = resolveNavigation('admin', defaultNavigationConfig())

    expect(keysOf(viewer)).not.toContain('learning-admin')
    expect(keysOf(viewer)).not.toContain('learning-mcp')
    expect(keysOf(admin)).toEqual(expect.arrayContaining(['learning-admin', 'learning-mcp']))
  })

  it('学习者视图把数据开发显示为实训台，管理员视图保留原名', () => {
    const viewer = resolveNavigation('viewer', defaultNavigationConfig())
    const admin = resolveNavigation('admin', defaultNavigationConfig())

    expect(viewer.find((module) => module.key === 'development')?.label).toBe('实训台')
    expect(admin.find((module) => module.key === 'development')?.label).toBe('数据开发')
  })
})

describe('navigation role gating', () => {
  it('viewer 看不到管理员专属入口，admin 能看到', () => {
    const viewer = resolveNavigation('viewer', defaultNavigationConfig())
    const admin = resolveNavigation('admin', defaultNavigationConfig())

    expect(keysOf(viewer)).not.toContain('admin-users')
    expect(keysOf(admin)).toContain('admin-users')
    expect(keysOf(viewer)).not.toContain('operations-audit')
    expect(keysOf(admin)).toContain('operations-audit')
  })

  it('没有任何可见二级项的一级菜单会被自动收起', () => {
    const config = defaultNavigationConfig()
    const modules = resolveNavigation('viewer', { ...config, hiddenItems: allNavChildren().map((child) => child.key) })
    expect(modules).toHaveLength(0)
  })
})

describe('navigation overrides', () => {
  it('可以把二级项改挂到另一个一级菜单', () => {
    const config = defaultNavigationConfig()
    config.itemModule = { 'assets-metrics': 'modeling' }

    const modules = resolveNavigation('admin', config)
    const modeling = modules.find((module) => module.key === 'modeling')
    const assets = modules.find((module) => module.key === 'assets')

    expect(modeling?.children.map((child) => child.key)).toContain('assets-metrics')
    expect(assets?.children.map((child) => child.key)).not.toContain('assets-metrics')
  })

  it('隐藏的二级项不再出现在导航与全局搜索里', () => {
    const config = defaultNavigationConfig()
    config.hiddenItems = ['analytics-history']

    const modules = resolveNavigation('admin', config)
    expect(keysOf(modules)).not.toContain('analytics-history')
    expect(flattenNavigation(modules).some((item) => item.path === '/analytics/history')).toBe(false)
  })

  it('隐藏的一级菜单连同其二级项一起消失', () => {
    const config = defaultNavigationConfig()
    config.hiddenModules = ['analytics']

    const modules = resolveNavigation('admin', config)
    expect(modules.some((module) => module.key === 'analytics')).toBe(false)
    expect(keysOf(modules)).not.toContain('analytics-query')
  })

  it('可以按 moduleOrder 调整一级顺序', () => {
    const config = defaultNavigationConfig()
    config.moduleOrder = ['admin', 'learning', ...config.moduleOrder.filter((key) => key !== 'admin' && key !== 'learning')]

    const modules = resolveNavigation('admin', config)
    expect(modules[0].key).toBe('admin')
    expect(modules[1].key).toBe('learning')
  })

  it('默认隐藏的模块可以通过 visibleModules 放回顶栏', () => {
    const config = defaultNavigationConfig()
    config.visibleModules = ['workbench']

    const modules = resolveNavigation('admin', config)
    expect(modules.some((module) => module.key === 'workbench')).toBe(true)
  })
})

describe('navigation config normalization', () => {
  it('丢弃未知模块与未知二级项，缺失字段回落到出厂值', () => {
    const normalized = normalizeNavigationConfig({
      hiddenModules: ['not-a-module', 'analytics'],
      hiddenItems: ['analytics-query'],
      itemModule: { 'assets-metrics': 'not-a-module' },
      moduleOrder: ['learning']
    })

    expect(normalized.hiddenModules).toEqual(['analytics'])
    expect(normalized.itemModule).toEqual({})
    expect(normalized.moduleOrder[0]).toBe('learning')
    // 未在顺序表里出现的一级菜单补在末尾，保证不丢菜单
    expect(normalized.moduleOrder).toHaveLength(navModules.length)
    expect(new Set(normalized.moduleOrder).size).toBe(navModules.length)
  })

  it('出厂归属与配置里的覆盖值互不影响', () => {
    const normalized = normalizeNavigationConfig({ itemModule: { 'assets-metrics': 'governance' } })
    expect(normalized.itemModule['assets-metrics']).toBe('governance')
    expect(defaultModuleOf('assets-metrics')).toBe('assets')
  })
})
