import { readFileSync, existsSync } from 'node:fs'
import { resolve } from 'node:path'
import { describe, expect, it } from 'vitest'

const webRoot = resolve(import.meta.dirname, '..')

describe('brand asset wiring', () => {
  it('keeps the document icon and in-app mark on the canonical AETHRIS asset', () => {
    const indexHtml = readFileSync(resolve(webRoot, 'index.html'), 'utf8')
    const brandMark = readFileSync(resolve(webRoot, 'src/components/BrandMark.tsx'), 'utf8')

    expect(existsSync(resolve(webRoot, 'public/AETHRIS.png'))).toBe(true)
    expect(indexHtml).toContain('href="/AETHRIS.png?v=20260930"')
    expect(indexHtml).not.toContain('/favicon.svg')
    expect(indexHtml).not.toContain('/aetheris-mark')
    expect(brandMark).toContain('src="/AETHRIS.png?v=20260930"')
    expect(brandMark).not.toContain('/favicon.svg')
    expect(brandMark).not.toContain('/aetheris-mark')
  })
})
