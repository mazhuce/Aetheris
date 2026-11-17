#!/usr/bin/env node
// Read-only learner smoke: login, learner IA, learning center, career page,
// expert drawer. Credentials come from AETHERIS_E2E_* env vars or the
// git-ignored configs/security/private/e2e-learner.env; never printed.
import { spawn } from 'node:child_process'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')
const origin = process.env.AETHERIS_WEB_ORIGIN ?? 'http://127.0.0.1:5173'
const chromePath = process.env.CHROME_PATH ?? 'C:/Program Files/Google/Chrome/Application/chrome.exe'
const port = 9500 + Math.floor(Math.random() * 300)

function readCredentials() {
  if (process.env.AETHERIS_E2E_USERNAME && process.env.AETHERIS_E2E_PASSWORD) {
    return { username: process.env.AETHERIS_E2E_USERNAME, password: process.env.AETHERIS_E2E_PASSWORD }
  }
  const file = path.join(root, 'configs', 'security', 'private', 'e2e-learner.env')
  if (!fs.existsSync(file)) {
    throw new Error('缺少测试账号：设置 AETHERIS_E2E_USERNAME/AETHERIS_E2E_PASSWORD 或提供 configs/security/private/e2e-learner.env')
  }
  const values = Object.fromEntries(fs.readFileSync(file, 'utf8').split(/\r?\n/)
    .filter((line) => line.includes('='))
    .map((line) => {
      const index = line.indexOf('=')
      return [line.slice(0, index), line.slice(index + 1)]
    }))
  return { username: values.E2E_LEARNER_USERNAME, password: values.E2E_LEARNER_PASSWORD }
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms))
async function waitFor(fn, timeout = 25000, interval = 300) {
  const end = Date.now() + timeout
  while (Date.now() < end) {
    try { if (await fn()) return true } catch { /* retry */ }
    await sleep(interval)
  }
  return false
}

async function main() {
  const credentials = readCredentials()
  if (!fs.existsSync(chromePath)) throw new Error(`Chrome 不存在：${chromePath}`)
  const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'aetheris-smoke-'))
  const chrome = spawn(chromePath, ['--headless=new', '--disable-gpu', `--remote-debugging-port=${port}`,
    `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check', '--window-size=1440,900',
    'about:blank'], { stdio: 'ignore' })

  let ws
  for (let i = 0; i < 80; i++) {
    try {
      const response = await fetch(`http://127.0.0.1:${port}/json/version`)
      if (response.ok) { ws = new WebSocket((await response.json()).webSocketDebuggerUrl); break }
    } catch { /* not ready */ }
    await sleep(250)
  }
  if (!ws) { chrome.kill(); throw new Error('Chrome DevTools 端点不可达') }
  await new Promise((resolve, reject) => { ws.addEventListener('open', resolve); ws.addEventListener('error', reject) })

  const pending = new Map()
  const consoleErrors = []
  let nextId = 1
  let sessionId
  ws.addEventListener('message', (event) => {
    const message = JSON.parse(event.data)
    if (message.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(String(message.params?.exceptionDetails?.text ?? 'exception').slice(0, 160))
    }
    if (message.id && pending.has(message.id)) {
      const { resolve, reject } = pending.get(message.id)
      pending.delete(message.id)
      message.error ? reject(new Error(JSON.stringify(message.error))) : resolve(message.result)
    }
  })
  function send(method, params = {}, session = sessionId) {
    const id = nextId++
    const payload = { id, method, params }
    if (session) payload.sessionId = session
    ws.send(JSON.stringify(payload))
    return new Promise((resolve, reject) => pending.set(id, { resolve, reject }))
  }

  const { targetId } = await send('Target.createTarget', { url: 'about:blank' })
  sessionId = (await send('Target.attachToTarget', { targetId, flatten: true })).sessionId
  await send('Page.enable')
  await send('Runtime.enable')
  await send('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false })

  async function evalJs(expression) {
    const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })
    if (result.exceptionDetails) throw new Error('页面脚本失败：' + JSON.stringify(result.exceptionDetails).slice(0, 200))
    return result.result.value
  }
  async function navReady() {
    return waitFor(async () => (await evalJs('document.querySelectorAll(".topnav-item-label").length')) > 0, 30000, 400)
  }
  async function gotoReady(url, settle = 2200) {
    await send('Page.navigate', { url })
    await waitFor(async () => (await evalJs('document.readyState')) === 'complete', 25000, 200)
    await navReady()
    await sleep(settle)
  }

  const checks = {}
  try {
    await gotoReady(`${origin}/login`, 1200)
    await evalJs(`(() => {
      const set = (el, value) => { const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value').set; setter.call(el, value); el.dispatchEvent(new Event('input', { bubbles: true })) }
      const inputs = [...document.querySelectorAll('input')]
      set(inputs[0], ${JSON.stringify(credentials.username)})
      inputs[1].focus()
    })()`)
    await send('Input.insertText', { text: credentials.password })
    await evalJs(`(() => { [...document.querySelectorAll('button')].find((button) => button.textContent.includes('进入工作空间')).click() })()`)
    checks.loggedIn = await waitFor(async () => !(await evalJs('location.pathname')).startsWith('/login'), 20000, 300)
    checks.shellReady = await navReady()

    const labels = await evalJs(`[...new Set([...document.querySelectorAll('.topnav-item-label')].map((el) => el.textContent.trim()))]`)
    const expected = ['学习中心', '实训台', '数据资产', '智能助手', '我的求职', '平台管理']
    checks.learnerNavigation = expected.every((label) => labels.includes(label)) && !labels.includes('内容运营')
    checks.navigationLabels = labels

    await gotoReady(`${origin}/learning`)
    checks.learningCenter = await waitFor(async () => (await evalJs('document.body.innerText')).includes('学习路径'), 20000, 400)

    await gotoReady(`${origin}/learning/portfolio`)
    checks.careerPortfolio = await waitFor(async () => (await evalJs('document.body.innerText')).includes('完成进度'), 20000, 400)

    checks.expertDrawer = await evalJs(`(() => { const button = document.querySelector('button[aria-label="打开数据开发专家"]'); if (!button) return false; button.click(); return true })()`)
      && await waitFor(async () => (await evalJs('document.body.innerText')).includes('数据开发专家'), 8000)
  } finally {
    try { await send('Target.closeTarget', { targetId }, undefined) } catch { /* ignore */ }
    ws.close()
    chrome.kill()
  }

  const failures = Object.entries(checks).filter(([, value]) => value === false).map(([key]) => key)
  const report = { ok: failures.length === 0, failures, checks, consoleErrors: consoleErrors.slice(0, 10) }
  console.log(JSON.stringify(report, null, 2))
  if (!report.ok) process.exit(1)
}

main().catch((error) => {
  console.error('learning smoke failed: ' + String(error.message ?? error))
  process.exit(1)
})