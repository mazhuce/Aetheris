import {
  createContext,
  useCallback,
  useContext,
  useLayoutEffect,
  useMemo,
  useState,
  type ReactNode
} from 'react'
import { ConfigProvider } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { getAppTheme } from '@/theme'
import {
  DEFAULT_PREFERENCES,
  applyPreferences,
  readPreferences,
  savePreferences,
  type AppPreferences
} from '@/utils/preferences'

interface PreferencesContextValue extends AppPreferences {
  updatePreferences: (next: Partial<AppPreferences>) => void
  resetPreferences: () => void
}

const PreferencesContext = createContext<PreferencesContextValue | null>(null)

export function PreferencesProvider({ children }: { children: ReactNode }) {
  const [preferences, setPreferences] = useState<AppPreferences>(readPreferences)

  useLayoutEffect(() => {
    applyPreferences(preferences)
    savePreferences(preferences)
  }, [preferences])

  const updatePreferences = useCallback((next: Partial<AppPreferences>) => {
    setPreferences((current) => ({ ...current, ...next }))
  }, [])

  const resetPreferences = useCallback(() => {
    setPreferences({ ...DEFAULT_PREFERENCES })
  }, [])

  const value = useMemo(() => ({
    ...preferences,
    updatePreferences,
    resetPreferences
  }), [preferences, resetPreferences, updatePreferences])

  return (
    <PreferencesContext.Provider value={value}>
      <ConfigProvider locale={zhCN} theme={getAppTheme(preferences.colorMode)}>
        {children}
      </ConfigProvider>
    </PreferencesContext.Provider>
  )
}

export function usePreferences(): PreferencesContextValue {
  const value = useContext(PreferencesContext)
  if (!value) throw new Error('usePreferences must be used inside PreferencesProvider')
  return value
}
