export function sessionUnavailable(message: string): Error & { code: string } {
  return Object.assign(new Error(message), { code: 'SESSION_UNAVAILABLE' })
}

/** Shared HttpOnly cookies require cross-tab serialization and a reliable change signal. */
export function requireSessionInfrastructure(): void {
  if (!navigator.locks?.request) {
    throw sessionUnavailable('当前浏览器无法安全协调多标签页会话，请在支持 Web Locks 的安全连接中打开')
  }
  const key = 'aetheris.session.probe.' + crypto.randomUUID()
  try {
    localStorage.setItem(key, key)
    if (localStorage.getItem(key) !== key) throw new Error('storage unavailable')
    localStorage.removeItem(key)
  } catch {
    throw sessionUnavailable('浏览器会话存储不可用，请允许本站存储后重新登录')
  }
}
