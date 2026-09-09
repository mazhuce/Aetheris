import type { TaskView } from '@/api/workflow'

export interface TaskOption {
  value: number
  label: string
}

export function sparkTaskOptions(tasks: TaskView[]): TaskOption[] {
  return tasks
    .filter(({ task }) => task.taskType === 'spark' && task.status === 'enabled')
    .map(({ task }) => ({ value: task.id, label: task.name }))
}

export function pipelineTargetOptions(tasks: TaskView[]): TaskOption[] {
  return tasks
    .filter(({ task }) => task.status === 'enabled')
    .map(({ task }) => ({
      value: task.id,
      label: `${task.name} · ${(task.taskType || 'sql').toUpperCase()}`
    }))
}
