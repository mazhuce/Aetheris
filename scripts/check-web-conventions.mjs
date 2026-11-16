#!/usr/bin/env node
// Enforces the typography half of docs/frontend-style.md: sizes must come from
// tokens.css. A deliberate exception is allowed when the same line carries a
// "例外：<reason>" comment. Structure (PageHeader/DataSurface) stays a review
// rule; this guard only catches mechanical drift.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const src = path.join(root, 'apps', 'web', 'src')
const skipDirs = new Set(['node_modules', 'dist', '.vite', '__pycache__'])

function walk(dir) {
  const out = []
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const full = path.join(dir, entry.name)
    if (entry.isDirectory()) {
      if (!skipDirs.has(entry.name)) out.push(...walk(full))
    } else if (/\.(css|tsx?|jsx?)$/.test(entry.name)) {
      out.push(full)
    }
  }
  return out
}

const cssSize = /font-size\s*:\s*[0-9.]+(px|rem|em)\b/
const inlineStringSize = /fontSize\s*:\s*['"`]\s*[0-9.]+(px|rem|em)/
const inlineNumberSize = /fontSize\s*=\s*\{\s*[0-9]/
const exception = '例外'

const files = walk(src)
const violations = []
for (const file of files) {
  const rel = path.relative(root, file).split(path.sep).join('/')
  const name = path.basename(file)
  if (name === 'tokens.css') continue
  const lines = fs.readFileSync(file, 'utf8').split(/\r?\n/)
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (line.includes(exception)) continue
    const hit = file.endsWith('.css')
      ? cssSize.test(line)
      : inlineStringSize.test(line) || inlineNumberSize.test(line)
    if (hit) violations.push(`${rel}:${i + 1}  ${line.trim().slice(0, 140)}`)
  }
}

if (violations.length > 0) {
  console.error('Web convention violations: font sizes must use tokens.css (--font-*).')
  console.error('Fix the line, or mark a deliberate exception on the same line: /* 例外：reason */')
  for (const item of violations) console.error('  ' + item)
  process.exit(1)
}
console.log(`Web conventions OK: scanned ${files.length} files under apps/web/src.`)