export type ColorMode = 'dark' | 'light'

export interface AppPreferences {
  colorMode: ColorMode
  reduceMotion: boolean
  homePath: string
}

export const PREFERENCES_KEY = 'aetheris-system-preferences'

export const DEFAULT_PREFERENCES: AppPreferences = {
  colorMode: 'dark',
  reduceMotion: false,
  homePath: '/workbench'
}

const ALLOWED_HOME_PATHS = new Set([
  '/workbench',
  '/workbench/operations',
  '/workbench/todos',
  '/workbench/recent'
])

export function readPreferences(): AppPreferences {
  try {
    const raw = localStorage.getItem(PREFERENCES_KEY)
    if (!raw) return { ...DEFAULT_PREFERENCES }

    const value = JSON.parse(raw) as Partial<AppPreferences>
    return {
      colorMode: value.colorMode === 'light' ? 'light' : 'dark',
      reduceMotion: value.reduceMotion === true,
      homePath: typeof value.homePath === 'string' && ALLOWED_HOME_PATHS.has(value.homePath)
        ? value.homePath
        : DEFAULT_PREFERENCES.homePath
    }
  } catch {
    return { ...DEFAULT_PREFERENCES }
  }
}

export function savePreferences(preferences: AppPreferences): void {
  try {
    localStorage.setItem(PREFERENCES_KEY, JSON.stringify(preferences))
  } catch {
    // 浏览器禁用本地存储时仍允许偏好在当前会话内生效。
  }
}

export function applyPreferences(preferences: AppPreferences): void {
  const root = document.documentElement
  root.dataset.theme = preferences.colorMode
  root.dataset.reduceMotion = String(preferences.reduceMotion)
  root.style.colorScheme = preferences.colorMode

  document.querySelector('meta[name="color-scheme"]')
    ?.setAttribute('content', preferences.colorMode)
  document.querySelector('meta[name="theme-color"]')
    ?.setAttribute('content', preferences.colorMode === 'dark' ? '#101114' : '#f5f6f8')
}
