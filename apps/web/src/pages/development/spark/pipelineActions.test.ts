import { describe, expect, it } from 'vitest'
import { canCancelPipeline, canResolveUnknownInstance, canRetryPipelineNode } from './pipelineActions'

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

  it('offers evidence-based resolution only for an unknown node with an instance', () => {
    expect(canResolveUnknownInstance('unknown', 19)).toBe(true)
    expect(canResolveUnknownInstance('unknown')).toBe(false)
    expect(canResolveUnknownInstance('failed', 19)).toBe(false)
  })
})
