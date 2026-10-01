import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const stylesRoot = resolve(import.meta.dirname)
const pagesCss = readFileSync(resolve(stylesRoot, 'pages.css'), 'utf8')
const responsiveCss = readFileSync(resolve(stylesRoot, 'responsive.css'), 'utf8')

describe('layout contracts', () => {
  it('keeps the theme segmented control compact on wide settings cards', () => {
    expect(pagesCss).toContain('.theme-mode-control {\n  align-self: flex-start;\n  width: max-content;')
  })

  it('anchors assistant mode copy and switch to stable desktop slots', () => {
    expect(pagesCss).toContain('.assistant-mode {\n  width: 304px;\n  flex: 0 0 304px;\n  justify-content: space-between;')
    expect(pagesCss).toContain('.assistant-mode > div {\n  width: 260px;\n  min-width: 0;\n  flex: 0 0 260px;')
    expect(responsiveCss).toContain('.assistant-mode {\n    width: auto;\n    flex: 0 0 auto;')
  })
})