import { describe, expect, it } from 'vitest'
import { canCancelPipeline, canRetryPipelineNode } from './pipelineActions'

describe('pipeline safety actions', () => {
  it('offers cancellation only while a pipeline is active', () => {
    expect(canCancelPipeline('queued')).toBe(true)
    expect(canCancelPipeline('running')).toBe(true)
    expect(canCancelPipeline('succeeded')).toBe(false)
    expect(canCancelPipeline('unknown')).toBe(false)
  })

  it('offers retry only from a confirmed failed or cancelled node', () => {
    expect(canRetryPipelineNode('failed', 'failed')).toBe(true)
    expect(canRetryPipelineNode('cancelled', 'cancelled')).toBe(true)
    expect(canRetryPipelineNode('unknown', 'unknown')).toBe(false)
    expect(canRetryPipelineNode('failed', 'skipped')).toBe(false)
  })
})
