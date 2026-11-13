import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode
} from 'react'
import {
  NAVIGATION_CONFIG_KEY,
  defaultNavigationConfig,
  flattenNavigation,
  normalizeNavigationConfig,
  resolveNavigation,
  type FlatNavItem,
  type NavChild,
  type NavModule,
  type NavigationConfig,
  type UserRole
} from '@/constants/navigation'
import { useAuthSession } from '@/hooks/useAuthSession'

interface NavigationContextValue {
  config: NavigationConfig
  updateConfig: (next: NavigationConfig) => void
  patchConfig: (patch: Partial<NavigationConfig>) => void
  resetConfig: () => void
}

const NavigationConfigContext = createContext<NavigationContextValue | null>(null)

function readConfig(): NavigationConfig {
  try {
    const raw = localStorage.getItem(NAVIGATION_CONFIG_KEY)
    if (!raw) return defaultNavigationConfig()
    return normalizeNavigationConfig(JSON.parse(raw))
  } catch {
    return defaultNavigationConfig()
  }
}

/**
 * 菜单配置的唯一数据源。
 *
 * 配置保存在浏览器本地，改完立刻重算导航树，因此顶栏、二级导航、全局搜索
 * 与「最近访问」会在同一次渲染里同步变化；路由本身不依赖配置，页面始终可达。
 */
export function NavigationProvider({ children }: { children: ReactNode }) {
  const [config, setConfig] = useState<NavigationConfig>(readConfig)

  useEffect(() => {
    try {
      localStorage.setItem(NAVIGATION_CONFIG_KEY, JSON.stringify(config))
    } catch {
      // 浏览器禁用本地存储时，配置仍在本会话内生效。
    }
  }, [config])

  // 同一浏览器的多个标签页之间保持同步，避免一处改了另一处还停在旧菜单。
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key !== NAVIGATION_CONFIG_KEY) return
      setConfig(event.newValue ? normalizeNavigationConfig(JSON.parse(event.newValue)) : defaultNavigationConfig())
    }
    window.addEventListener('storage', onStorage)
    return () => window.removeEventListener('storage', onStorage)
  }, [])

  const updateConfig = useCallback((next: NavigationConfig) => {
    setConfig(normalizeNavigationConfig(next))
  }, [])

  const patchConfig = useCallback((patch: Partial<NavigationConfig>) => {
    setConfig((current) => normalizeNavigationConfig({ ...current, ...patch }))
  }, [])

  const resetConfig = useCallback(() => {
    setConfig(defaultNavigationConfig())
  }, [])

  const value = useMemo(
    () => ({ config, updateConfig, patchConfig, resetConfig }),
    [config, patchConfig, resetConfig, updateConfig]
  )

  return <NavigationConfigContext.Provider value={value}>{children}</NavigationConfigContext.Provider>
}

/**
 * 读取菜单配置。未包裹 Provider 时（单测、局部预览）回落到出厂配置，
 * 避免把「缺 Provider」变成一屏白屏。
 */
export function useNavigationConfig(): NavigationContextValue {
  const value = useContext(NavigationConfigContext)
  if (value) return value
  return FALLBACK_VALUE
}

const FALLBACK_VALUE: NavigationContextValue = {
  config: defaultNavigationConfig(),
  updateConfig: () => undefined,
  patchConfig: () => undefined,
  resetConfig: () => undefined
}

export interface ResolvedNavigation {
  role: UserRole
  modules: NavModule[]
  items: FlatNavItem[]
  findModule: (pathname: string) => NavModule | undefined
  findChild: (pathname: string) => { module: NavModule; child: NavChild } | undefined
}

/** 当前角色 + 当前配置下的导航树。 */
export function useNavigation(): ResolvedNavigation {
  const session = useAuthSession()
  const role: UserRole = session.status === 'authenticated' && session.user.role === 'admin' ? 'admin' : 'viewer'
  const { config } = useNavigationConfig()

  return useMemo(() => {
    const modules = resolveNavigation(role, config)
    const items = flattenNavigation(modules)

    const findChild = (pathname: string) => {
      let best: { module: NavModule; child: NavChild } | undefined
      for (const module of modules) {
        for (const child of module.children) {
          if (pathname === child.path || pathname.startsWith(child.path + '/')) {
            if (!best || child.path.length > best.child.path.length) best = { module, child }
          }
        }
      }
      return best
    }

    return {
      role,
      modules,
      items,
      findChild,
      findModule: (pathname: string) => findChild(pathname)?.module
    }
  }, [config, role])
}
