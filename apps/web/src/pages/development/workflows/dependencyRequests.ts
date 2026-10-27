import type { TaskDependency } from '@/api/workflow'

export const DEPENDENCY_CONCURRENCY = 4

/** Shared by graph workers and detail reads; abort never releases a still-running transport slot. */
export class DependencyRequestQueue {
  private active = 0
  private waiting: (() => void)[] = []

  run<T>(read: () => Promise<T>, signal: AbortSignal): Promise<T> {
    return new Promise<T>((resolve, reject) => {
      if (signal.aborted) { reject(new DOMException('Cancelled', 'AbortError')); return }
      let started = false
      const cancel = () => {
        if (started) return
        this.waiting = this.waiting.filter((entry) => entry !== start)
        signal.removeEventListener('abort', cancel)
        reject(new DOMException('Cancelled', 'AbortError'))
      }
      const start = () => {
        started = true
        this.active++
        void Promise.resolve().then(() => {
          if (signal.aborted) throw new DOMException('Cancelled', 'AbortError')
          return read()
        }).then(resolve, reject).finally(() => {
          signal.removeEventListener('abort', cancel)
          this.active--
          this.drain()
        })
      }
      signal.addEventListener('abort', cancel, { once: true })
      this.waiting.push(start)
      this.drain()
    })
  }

  private drain() {
    while (this.active < DEPENDENCY_CONCURRENCY && this.waiting.length) this.waiting.shift()!()
  }
}

export function checkedDependencies(taskId: number, rows: TaskDependency[]): TaskDependency[] {
  if (!Array.isArray(rows) || rows.some((row) => !row || row.taskId !== taskId
      || !Number.isSafeInteger(row.id) || row.id <= 0
      || !Number.isSafeInteger(row.upstreamTaskId) || row.upstreamTaskId <= 0)) {
    throw new Error('DEPENDENCY_OWNER_MISMATCH')
  }
  return rows
}

export function dependencyFailure(error: unknown): string {
  const failure = error as { response?: { status?: number }; code?: string }
  if (failure?.response?.status === 403) return '无权读取该任务的依赖'
  if (failure?.response?.status === 503) return '依赖服务暂时不可用'
  if (failure?.code === 'ECONNABORTED' || failure?.code === 'ETIMEDOUT') return '依赖请求超时'
  return '未能确认依赖，请重试'
}
