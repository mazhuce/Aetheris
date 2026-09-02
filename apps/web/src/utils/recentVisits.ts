/**
 * 最近访问记录（纯本地，零后端）：
 * - BasicLayout 每次路由变化时调用 record()
 * - workbench/recent 页面读取展示
 * 存储按登录用户隔离，最多 20 条，去重保序（最近优先）。
 */
const STORAGE_KEY = 'aetheris-recent-visits'
const MAX_ITEMS = 20

export interface RecentVisit {
  path: string
  label: string
  moduleLabel: string
  visitedAt: number
}

function storageKey(username?: string): string {
  return username ? `${STORAGE_KEY}:${username}` : STORAGE_KEY
}

export function recordRecentVisit(
  path: string,
  label: string,
  moduleLabel: string,
  username?: string
): void {
  try {
    const key = storageKey(username)
    const list = readRecentVisits(username)
    const next = [
      { path, label, moduleLabel, visitedAt: Date.now() },
      ...list.filter((item) => item.path !== path)
    ].slice(0, MAX_ITEMS)
    localStorage.setItem(key, JSON.stringify(next))
  } catch {
    // localStorage 不可用（隐私模式/配额）时静默放弃，记录是纯增强能力
  }
}

export function readRecentVisits(username?: string): RecentVisit[] {
  try {
    const raw = localStorage.getItem(storageKey(username))
    if (!raw) return []
    const parsed = JSON.parse(raw)
    return Array.isArray(parsed) ? parsed : []
  } catch {
    return []
  }
}

export function clearRecentVisits(username?: string): void {
  try {
    localStorage.removeItem(storageKey(username))
  } catch {
    // 同上，静默
  }
}
