import http from './http'

/** 任务定义。字段与后端 workflow.task 表一致。 */
export interface Task {
  id: number
  name: string
  taskType?: string
  datasourceId: number
  datasourceName?: string
  taskSql: string
  /** Quartz cron（6 或 7 段）；为空表示仅手动触发。 */
  cronExpression?: string
  targetTable?: string
  timeoutSeconds?: number
  maxRetries?: number
  status?: 'enabled' | 'disabled'
  owner?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface TaskInput {
  name: string
  datasourceId: number
  datasourceName?: string
  taskSql: string
  cronExpression?: string
  targetTable?: string
  timeoutSeconds?: number
  maxRetries?: number
  status?: string
  description?: string
}

/** 列表视图：任务定义 + 调度状态 + 最近一次结果，后端一次返回避免 N+1 请求。 */
export interface TaskView {
  task: Task
  nextFireTime?: string
  lastStatus?: TaskInstanceStatus
  lastBizDate?: string
  lastFinishedAt?: string
  upstreamCount: number
}

export type TaskInstanceStatus = 'pending' | 'running' | 'success' | 'failed' | 'skipped'

export interface TaskInstance {
  id: number
  taskId: number
  /** 业务日期（数据日期），对应分区键 dt，不是执行时刻。 */
  bizDate: string
  status: TaskInstanceStatus
  triggerType?: 'manual' | 'schedule' | 'retry'
  attempt?: number
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  /** Hive 驱动不返回行数时为 -1，表示「未知」而非 0 行。 */
  affectedRows?: number
  errorMessage?: string
  triggeredBy?: string
}

export interface TaskLog {
  id: number
  instanceId: number
  level: 'info' | 'warn' | 'error'
  message: string
  createdAt?: string
}

export interface TaskDependency {
  id: number
  taskId: number
  upstreamTaskId: number
}

/** 实例状态的中文名与配色，供表格与详情统一使用。 */
export const INSTANCE_STATUS_META: Record<
  TaskInstanceStatus,
  { label: string; color: string }
> = {
  pending: { label: '等待中', color: 'default' },
  running: { label: '执行中', color: 'processing' },
  success: { label: '成功', color: 'success' },
  // skipped 是「依赖未就绪」而非故障，用 warning 与 failed 区分开，
  // 否则会让人误以为任务出错。
  skipped: { label: '已跳过', color: 'warning' },
  failed: { label: '失败', color: 'error' }
}

export function listTasks(): Promise<TaskView[]> {
  return http.get<TaskView[]>('/api/workflow/tasks').then((res) => res.data)
}

export function getTask(id: number): Promise<TaskView> {
  return http.get<TaskView>(`/api/workflow/tasks/${id}`).then((res) => res.data)
}

export function createTask(payload: TaskInput): Promise<TaskView> {
  return http.post<TaskView>('/api/workflow/tasks', payload).then((res) => res.data)
}

export function updateTask(id: number, payload: TaskInput): Promise<TaskView> {
  return http.put<TaskView>(`/api/workflow/tasks/${id}`, payload).then((res) => res.data)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`/api/workflow/tasks/${id}`).then(() => undefined)
}

/**
 * 手动触发任务。
 *
 * 后端同步执行，加工任务可能跑很久，因此单独放宽超时；
 * 即便前端超时，执行状态仍会落在实例表里，刷新可见。
 */
export function runTask(id: number, bizDate?: string): Promise<TaskInstance> {
  return http
    .post<TaskInstance>(`/api/workflow/tasks/${id}/run`, { bizDate }, { timeout: 300000 })
    .then((res) => res.data)
}

export function listInstances(id: number, limit = 30): Promise<TaskInstance[]> {
  return http
    .get<TaskInstance[]>(`/api/workflow/tasks/${id}/instances`, { params: { limit } })
    .then((res) => res.data)
}

export function listInstanceLogs(instanceId: number): Promise<TaskLog[]> {
  return http
    .get<TaskLog[]>(`/api/workflow/tasks/instances/${instanceId}/logs`)
    .then((res) => res.data)
}

export function listDependencies(id: number): Promise<TaskDependency[]> {
  return http
    .get<TaskDependency[]>(`/api/workflow/tasks/${id}/dependencies`)
    .then((res) => res.data)
}

export function addDependency(id: number, upstreamTaskId: number): Promise<TaskDependency> {
  return http
    .post<TaskDependency>(`/api/workflow/tasks/${id}/dependencies`, undefined, {
      params: { upstreamTaskId }
    })
    .then((res) => res.data)
}

export function removeDependency(id: number, upstreamTaskId: number): Promise<void> {
  return http
    .delete(`/api/workflow/tasks/${id}/dependencies/${upstreamTaskId}`)
    .then(() => undefined)
}
