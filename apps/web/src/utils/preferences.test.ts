import { beforeEach, describe, expect, it } from 'vitest'
import {
  DEFAULT_PREFERENCES,
  PREFERENCES_KEY,
  applyPreferences,
  readPreferences,
  savePreferences
} from './preferences'

describe('system preferences', () => {
  beforeEach(() => {
    localStorage.clear()
    delete document.documentElement.dataset.theme
    delete document.documentElement.dataset.reduceMotion
  })

  it('uses the existing dark experience by default', () => {
    expect(readPreferences()).toEqual(DEFAULT_PREFERENCES)
  })

  it('persists valid preferences', () => {
    const preferences = {
      colorMode: 'light' as const,
      reduceMotion: true,
      homePath: '/workbench/recent'
    }

    savePreferences(preferences)
    expect(readPreferences()).toEqual(preferences)
  })

  it('falls back safely when stored values are invalid', () => {
    localStorage.setItem(PREFERENCES_KEY, JSON.stringify({
      colorMode: 'blue',
      reduceMotion: 'yes',
      homePath: '/admin/users'
    }))

    expect(readPreferences()).toEqual(DEFAULT_PREFERENCES)
  })

  it('applies appearance and motion preferences to the document', () => {
    applyPreferences({ colorMode: 'light', reduceMotion: true, homePath: '/workbench' })

    expect(document.documentElement.dataset.theme).toBe('light')
    expect(document.documentElement.dataset.reduceMotion).toBe('true')
    expect(document.documentElement.style.colorScheme).toBe('light')
  })
})
