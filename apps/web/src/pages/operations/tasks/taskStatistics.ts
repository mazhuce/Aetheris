import type { TaskInstance } from '@/api/workflow'

/** 只对结果已确认的完成实例计算成功率；其余状态保留各自计数。 */
export function summarizeTaskStatuses(instances: readonly Pick<TaskInstance, 'status'>[]) {
  const counts = { success: 0, failed: 0, timedOut: 0, cancelled: 0, skipped: 0, unknown: 0, inProgress: 0 }
  for (const instance of instances) {
    switch (instance.status) {
      case 'success': counts.success++; break
      case 'failed': counts.failed++; break
      case 'timed_out': counts.timedOut++; break
      case 'cancelled': counts.cancelled++; break
      case 'skipped': counts.skipped++; break
      case 'pending':
      case 'queued':
      case 'submitting':
      case 'running':
      case 'cancel_requested': counts.inProgress++; break
      default: counts.unknown++; break
    }
  }
  const knownCompleted = counts.success + counts.failed + counts.timedOut
  return {
    ...counts,
    knownCompleted,
    successRate: knownCompleted === 0 ? null : Math.round((counts.success / knownCompleted) * 100)
  }
}
