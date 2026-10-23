// Only an opaque revision is shared. Access/refresh tokens remain HttpOnly cookies.
const REVISION_KEY = 'aetheris.session.revision'

export function sessionRevision(): string {
  try { return localStorage.getItem(REVISION_KEY) ?? '' } catch { return '' }
}

export async function coordinateRefresh(observed: string, refresh: () => Promise<void>): Promise<void> {
  const run = async () => {
    // Another tab has already rotated the shared cookies since this request began.
    if (sessionRevision() !== observed) return
    await refresh()
    try { localStorage.setItem(REVISION_KEY, crypto.randomUUID()) } catch { /* Storage can be disabled. */ }
  }
  if (navigator.locks?.request) {
    await navigator.locks.request('aetheris.session.refresh', run)
  } else {
    await run()
  }
}

export async function waitForConcurrentRefresh(observed: string): Promise<void> {
  // Older browsers without Web Locks can lose the server CAS race. Give the winning
  // response a bounded chance to install cookies, then the caller verifies /me.
  const deadline = Date.now() + 500
  while (sessionRevision() === observed && Date.now() < deadline) {
    await new Promise((resolve) => window.setTimeout(resolve, 25))
  }
}
