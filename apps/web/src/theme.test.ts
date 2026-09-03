import { describe, expect, it } from 'vitest'
import { getAppTheme } from './theme'

describe('app theme', () => {
  it('keeps status colours stable across modes', () => {
    const dark = getAppTheme('dark').token
    const light = getAppTheme('light').token

    expect(light?.colorSuccess).toBe(dark?.colorSuccess)
    expect(light?.colorWarning).toBe(dark?.colorWarning)
    expect(light?.colorError).toBe(dark?.colorError)
  })

  it('uses distinct neutral surfaces and text colours', () => {
    const dark = getAppTheme('dark').token
    const light = getAppTheme('light').token

    expect(dark?.colorBgLayout).toBe('#101114')
    expect(light?.colorBgLayout).toBe('#f5f6f8')
    expect(dark?.colorText).toBe('#eeeef1')
    expect(light?.colorText).toBe('#181a1f')
  })
})
