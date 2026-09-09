import type { PipelineNodeRun, PipelineRun } from '@/api/workflow'

export function canCancelPipeline(status: PipelineRun['status']): boolean {
  return status === 'queued' || status === 'running'
}

export function canRetryPipelineNode(
  runStatus: PipelineRun['status'],
  nodeStatus: PipelineNodeRun['status']
): boolean {
  return (runStatus === 'failed' || runStatus === 'cancelled')
    && (nodeStatus === 'failed' || nodeStatus === 'cancelled')
}
