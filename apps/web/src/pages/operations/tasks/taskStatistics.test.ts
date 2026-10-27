import { describe, expect, it } from 'vitest'
import type { TaskInstanceStatus } from '@/api/workflow'
import { summarizeTaskStatuses } from './taskStatistics'

const summarize = (...statuses: TaskInstanceStatus[]) => summarizeTaskStatuses(statuses.map((status) => ({ status })))

describe('known completed task success rate', () => {
  it('has no sample for an empty list', () => {
    expect(summarize()).toMatchObject({ knownCompleted: 0, successRate: null })
  })

  it('counts timeouts as unsuccessful known completions', () => {
    expect(summarize('timed_out', 'timed_out')).toMatchObject({ timedOut: 2, knownCompleted: 2, successRate: 0 })
  })

  it('includes both failure kinds in the denominator and keeps excluded states separate', () => {
    expect(summarize('success', 'success', 'failed', 'timed_out', 'cancelled', 'skipped', 'unknown', 'running')).toEqual({
      success: 2, failed: 1, timedOut: 1, cancelled: 1, skipped: 1, unknown: 1, inProgress: 1,
      knownCompleted: 4, successRate: 50
    })
  })

  it.each<TaskInstanceStatus>(['pending', 'queued', 'submitting', 'running', 'cancel_requested'])(
    'keeps %s outside known completions',
    (status) => {
      expect(summarize(status)).toMatchObject({ inProgress: 1, knownCompleted: 0, successRate: null })
    }
  )

  it.each<TaskInstanceStatus>(['cancelled', 'skipped', 'unknown'])(
    'does not treat %s as a success or unsuccessful completion',
    (status) => {
      expect(summarize(status)).toMatchObject({ success: 0, knownCompleted: 0, successRate: null })
    }
  )

  it('can report 100 percent only when a known success sample exists', () => {
    expect(summarize('success', 'cancelled', 'skipped', 'unknown')).toMatchObject({ knownCompleted: 1, successRate: 100 })
  })

  it('rounds the known completion ratio to an integer percentage', () => {
    expect(summarize('success', 'failed', 'timed_out').successRate).toBe(33)
  })

  it('keeps an unrecognized runtime status unresolved', () => {
    expect(summarize('future_status' as TaskInstanceStatus)).toMatchObject({ unknown: 1, knownCompleted: 0, successRate: null })
  })
})
