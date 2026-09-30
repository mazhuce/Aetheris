import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const webRoot = resolve(import.meta.dirname, '..')

describe('brand asset wiring', () => {
  it('keeps the document icon and in-app mark on the canonical favicon asset', () => {
    const indexHtml = readFileSync(resolve(webRoot, 'index.html'), 'utf8')
    const brandMark = readFileSync(resolve(webRoot, 'src/components/BrandMark.tsx'), 'utf8')

    expect(existsSync(resolve(webRoot, 'public/favicon.svg'))).toBe(true)
    expect(indexHtml).toContain('href="/favicon.svg?v=20260930-mono"')
    expect(indexHtml).not.toContain('/AETHRIS.png')
    expect(indexHtml).not.toContain('/aetheris-mark')
    expect(brandMark).toContain('src="/favicon.svg?v=20260930-mono"')
    expect(brandMark).not.toContain('/AETHRIS.png')
    expect(brandMark).not.toContain('/aetheris-mark')
  })
})