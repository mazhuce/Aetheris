import { describe, expect, it } from 'vitest'
import type { TaskView } from '@/api/workflow'
import { pipelineTargetOptions, sparkTaskOptions } from './taskOptions'

const tasks: TaskView[] = [
  { task: { id: 1, name: 'DWD SQL', taskType: 'sql', status: 'enabled' }, upstreamCount: 0 },
  { task: { id: 2, name: 'Spark 清洗', taskType: 'spark', status: 'enabled' }, upstreamCount: 1 },
  { task: { id: 3, name: '质量门禁', taskType: 'quality', status: 'enabled' }, upstreamCount: 1 },
  { task: { id: 4, name: '已停用 ADS', taskType: 'sql', status: 'disabled' }, upstreamCount: 1 }
]

describe('Spark development task selectors', () => {
  it('keeps individual execution limited to enabled Spark tasks', () => {
    expect(sparkTaskOptions(tasks)).toEqual([{ value: 2, label: 'Spark 清洗' }])
  })

  it('allows every enabled task type to be the dependency-chain target', () => {
    expect(pipelineTargetOptions(tasks)).toEqual([
      { value: 1, label: 'DWD SQL · SQL' },
      { value: 2, label: 'Spark 清洗 · SPARK' },
      { value: 3, label: '质量门禁 · QUALITY' }
    ])
  })
})
