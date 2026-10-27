/* Real Chromium and HTTP services. No mocked API responses or injected application state. */
const fs = require('node:fs')
const assert = require('node:assert/strict')
const config = JSON.parse(fs.readFileSync(0, 'utf8'))
const { chromium } = require(config.playwright)
const checks = []
const report = { engine: 'Chromium', checks, closed: false }
let browser
const pause = ms => new Promise(resolve => setTimeout(resolve, ms))
const origin = config.origin
async function control(op, extra = {}) {
  const response = await fetch(origin + '/__audit_control', { method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Audit-Control': config.controlSecret },
    body: JSON.stringify({ op, ...extra }), signal: AbortSignal.timeout(110000) })
  assert.equal(response.status, 200, 'owned control operation failed')
  return response.json()
}
async function held(path, status) {
  const deadline = Date.now() + 30000
  while (Date.now() < deadline) {
    const state = await control('state')
    if (state.held) {
      assert.equal(state.held.path, path); assert.equal(state.held.status, status); return
    }
    await pause(100)
  }
  throw new Error('actual response did not reach the controlled hold')
}
async function api(page, path, method = 'GET', body) {
  return page.evaluate(async ({ path, method, body }) => {
    const response = await fetch(path, { method, headers: body === undefined ? {} : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body), cache: 'no-store' })
    const text = await response.text()
    return { status: response.status, data: text ? JSON.parse(text) : null }
  }, { path, method, body }).catch(error => ({ status: 0, data: null, transportError: error.name }))
}
function cookieHeader(cookies) { return cookies.map(c => c.name + '=' + c.value).join('; ') }
function claims(cookie) { return JSON.parse(Buffer.from(cookie.value.split('.')[1], 'base64url').toString()) }
function cookieOf(cookies, type) { return cookies.find(c => { try { return claims(c).typ === type } catch { return false } }) }
async function direct(path, method, cookies) {
  const response = await fetch(origin + path, { method, headers: { Cookie: cookieHeader(cookies) }, signal: AbortSignal.timeout(45000) })
  const text = await response.text()
  return { status: response.status, data: text ? JSON.parse(text) : null, cookieHeaders: response.headers.getSetCookie().length }
}
async function login(page, username = config.username, password = config.password) {
  await page.goto(origin + '/login')
  await page.getByLabel('用户名', { exact: true }).fill(username)
  await page.getByLabel('密码', { exact: true }).fill(password)
  const received = page.waitForResponse(r => r.url() === origin + '/api/auth/login' && r.request().method() === 'POST')
  await page.getByRole('button', { name: '进入工作空间' }).click()
  const response = await received
  assert.equal(response.status(), 200, 'real UI login rejected')
  const user = await response.json()
  await page.waitForURL(url => url.pathname !== '/login')
  return user
}
async function logoutUi(page) {
  await page.getByRole('button', { name: '打开账户菜单' }).click()
  const response = page.waitForResponse(r => r.url() === origin + '/api/auth/logout' && r.request().method() === 'POST')
  await page.getByText('退出登录', { exact: true }).click()
  return (await response).status()
}
const warning = '本地已退出，服务端会话撤销尚未确认'
async function runCase(name, operation) {
  const context = await browser.newContext()
  const page = await context.newPage()
  page.setDefaultTimeout(25000)
  const began = Date.now()
  try {
    const details = await operation(context, page)
    checks.push({ case: name, passed: true, durationMs: Date.now() - began, ...details })
  } catch (error) {
    // Assertions contain statuses/booleans only; never emit a cookie, password or page payload.
    checks.push({ case: name, passed: false, durationMs: Date.now() - began,
      failure: { type: error.name, message: String(error.message).slice(0, 1400).replace(/eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+/g, '[REDACTED_JWT]') } })
  } finally {
    await control('release').catch(() => {})
    await control('gateway', { name: 'gateway' }).catch(() => {})
    await context.close()
  }
}
async function main() {
  browser = await chromium.launch({ executablePath: config.executable, headless: true })
  report.browserVersion = browser.version()
  // Create B through actual authorized public API; no direct user-table fixture insertion.
  const setup = await browser.newContext(); const setupPage = await setup.newPage()
  await login(setupPage)
  const viewer = 'browser_viewer_' + config.runId.slice(0, 8)
  const created = await api(setupPage, '/api/auth/users', 'POST', { username: viewer, password: config.password,
    role: 'viewer', status: 'active', nickname: '隔离会话验收用户' })
  assert.equal(created.status, 201, 'fixture viewer creation failed')
  const viewerId = created.data.id
  await setup.close()

  await runCase('ui-login-two-tabs', async (context, page) => {
    const user = await login(page); const second = await context.newPage()
    await second.goto(origin + '/account')
    await second.getByRole('button', { name: '打开账户菜单' }).waitFor()
    const profiles = await Promise.all([api(page, '/api/auth/me'), api(second, '/api/auth/me')])
    assert(profiles.every(r => r.status === 200 && r.data.id === user.id), 'tabs have differing actual principals')
    const cookies = await context.cookies()
    assert(cookies.length >= 3 && cookies.every(c => c.httpOnly && c.sameSite === 'Lax'), 'browser credential cookie flags missing')
    return { actualProfileCount: 2, httpOnly: true }
  })

  let replayCookies
  await runCase('two-tab-refresh-single-rotation', async (context, page) => {
    const user = await login(page); replayCookies = await context.cookies()
    const second = await context.newPage(); await second.goto(origin + '/account')
    await second.getByRole('button', { name: '打开账户菜单' }).waitFor()
    for (const c of await context.cookies()) if (c.name.startsWith('aetheris_access')) await context.clearCookies({ name: c.name })
    const before = (await control('state')).requests['/api/auth/refresh'] || 0
    await Promise.all([page.goto(origin + '/account'), second.reload()])
    await Promise.all([page, second].map(p => p.getByRole('button', { name: '打开账户菜单' }).waitFor()))
    const after = (await control('state')).requests['/api/auth/refresh'] || 0
    assert.equal(after - before, 1, 'two legitimate tabs must share one refresh rotation')
    const me = await api(page, '/api/auth/me'); assert.equal(me.data.id, user.id)
    return { actualRefreshRequests: after - before }
  })
  await runCase('consumed-refresh-denied', async () => {
    assert(replayCookies, 'previous refresh setup missing')
    const replay = await direct('/api/auth/refresh', 'POST', replayCookies)
    assert.equal(replay.status, 401); assert.equal(replay.cookieHeaders, 0, 'replay erases winning cookies')
    return { status: replay.status, cookieHeaders: replay.cookieHeaders }
  })

  await runCase('late-refresh-after-logout-stays-revoked', async (context, page) => {
    await login(page)
    await control('hold', { path: '/api/auth/refresh' })
    const late = api(page, '/api/auth/refresh', 'POST'); await held('/api/auth/refresh', 200)
    assert.equal(await logoutUi(page), 204)
    await control('release'); assert.equal((await late).status, 200)
    const me = await api(page, '/api/auth/me'); assert.equal(me.status, 401, 'late cookies revived revoked family')
    await page.reload(); await page.getByRole('button', { name: '进入工作空间' }).waitFor()
    return { lateStatus: 200, actualProfileStatus: me.status }
  })
  await runCase('late-active-A-refresh-cannot-replace-B', async (context, page) => {
    await login(page); const oldTab = await context.newPage(); await oldTab.goto(origin + '/login')
    await control('hold', { path: '/api/auth/refresh' })
    const late = api(oldTab, '/api/auth/refresh', 'POST'); await held('/api/auth/refresh', 200)
    await login(page, viewer); await control('release'); assert.equal((await late).status, 200)
    const me = await api(page, '/api/auth/me')
    assert.equal(me.status, 200); assert.equal(me.data.id, viewerId, 'late A refresh changed the actual B principal')
    return { actualBProfile: true }
  })
  for (const [name, fault, status] of [['late-A-logout-cannot-clear-B', null, 204],
    ['late-invalid-logout-cannot-clear-B', 'invalid', 401], ['late-unconfirmed-logout-cannot-clear-B', 'database', 503]]) {
    await runCase(name, async (context, page) => {
      await login(page); const oldTab = await context.newPage(); await oldTab.goto(origin + '/login')
      if (fault === 'invalid') for (const c of await context.cookies()) if (c.name.startsWith('aetheris_logout')) await context.clearCookies({ name: c.name })
      if (fault === 'database') await control('pause', { name: 'postgres' })
      try {
        await control('hold', { path: '/api/auth/logout' })
        const late = api(oldTab, '/api/auth/logout', 'POST'); await held('/api/auth/logout', status)
        if (fault === 'database') await control('unpause', { name: 'postgres' })
        await login(page, viewer); await control('release'); assert.equal((await late).status, status)
        const me = await api(page, '/api/auth/me')
        assert.equal(me.status, 200, 'late A logout erased B cookies'); assert.equal(me.data.id, viewerId)
        return { lateStatus: status, actualBProfile: true }
      } finally { if (fault === 'database') await control('unpause', { name: 'postgres' }).catch(() => {}) }
    })
  }

  await runCase('old-family-logout-does-not-revoke-B', async (context, page) => {
    await login(page); const oldCookies = await context.cookies(); const old = cookieOf(oldCookies, 'browser-logout')
    const oldSelector = oldCookies.find(c => c.name === 'aetheris_session')
    await login(page, viewer); const current = await context.cookies()
    const mixed = current.filter(c => c.name !== old.name && c.name !== 'aetheris_session'); mixed.push(old)
    if (oldSelector) mixed.push(oldSelector)
    assert.equal((await direct('/api/auth/logout', 'POST', mixed)).status, 204)
    const me = await api(page, '/api/auth/me'); assert.equal(me.status, 200); assert.equal(me.data.id, viewerId)
    return { actualBProfile: true }
  })
  await runCase('revocation-survives-second-gateway-and-restart', async (context, page) => {
    await login(page); const old = await context.cookies(); assert.equal(await logoutUi(page), 204)
    await control('gateway', { name: 'gateway2' }); assert.equal((await direct('/api/auth/me', 'GET', old)).status, 401)
    await control('restart', { name: 'auth' }); await control('restart', { name: 'gateway2' })
    assert.equal((await direct('/api/auth/me', 'GET', old)).status, 401)
    return { secondGatewayDenied: true, restartedAuthAndGatewayDenied: true }
  })
  await runCase('redis-down-logout', async (context, page) => {
    await login(page); const old = await context.cookies(); await control('pause', { name: 'redis' })
    try { assert.equal(await logoutUi(page), 204, 'Redis failure blocked durable logout') }
    finally { await control('unpause', { name: 'redis' }) }
    assert.equal((await direct('/api/auth/me', 'GET', old)).status, 401)
    return { logoutStatus: 204, oldProfileStatus: 401 }
  })
  await runCase('database-down-unconfirmed-ui-and-retry', async (context, page) => {
    await login(page); const old = await context.cookies(); await control('pause', { name: 'postgres' })
    try {
      assert.equal(await logoutUi(page), 503, 'unconfirmed revocation was reported as success')
      await page.getByText(warning, { exact: true }).waitFor(); await page.reload()
      await page.getByText(warning, { exact: true }).waitFor()
    } finally { await control('unpause', { name: 'postgres' }) }
    await page.getByLabel('用户名', { exact: true }).fill(config.username)
    await page.getByLabel('密码', { exact: true }).fill('deliberately-wrong-fixture-password')
    const loginFailure = page.waitForResponse(r => r.url() === origin + '/api/auth/login')
    await page.getByRole('button', { name: '进入工作空间' }).click(); assert.equal((await loginFailure).status(), 401)
    const freshTab = await context.newPage(); await freshTab.goto(origin + '/login')
    await freshTab.getByText(warning, { exact: true }).waitFor()
    const retry = freshTab.waitForResponse(r => r.url() === origin + '/api/auth/logout')
    await freshTab.getByRole('button', { name: '重试退出' }).click(); assert.equal((await retry).status(), 204)
    await freshTab.getByText(warning, { exact: true }).waitFor({ state: 'hidden' })
    assert.equal((await direct('/api/auth/me', 'GET', old)).status, 401)
    return { failure503Visible: true, reloadAndFailedLoginPreservedWarning: true, actualRetry204: true }
  })
  await runCase('expired-access-and-consumed-refresh-still-logout', async (context, page) => {
    await login(page); const old = await context.cookies(); const access = cookieOf(old, 'access')
    const consumed = cookieOf(old, 'refresh')
    assert.equal((await api(page, '/api/auth/refresh', 'POST')).status, 200)
    const current = await context.cookies()
    await pause(Math.max(0, claims(access).exp * 1000 + 1100 - Date.now()))
    // Extend only browser retention; the signed JWT itself is now expired. Refresh is the consumed version.
    await context.addCookies([access, consumed].map(c => ({ ...c, expires: Date.now() / 1000 + 90 })))
    assert.equal(await logoutUi(page), 204)
    assert.equal((await direct('/api/auth/refresh', 'POST', current)).status, 401)
    return { actualAccessExpired: claims(access).exp * 1000 < Date.now(), consumedRefresh: true, independentLogout204: true }
  })
}
main().catch(error => { report.fatal = { type: error.name, message: String(error.message).slice(0, 1000) } })
  .finally(async () => { if (browser) { await browser.close(); report.closed = true }; process.stdout.write(JSON.stringify(report)) })
