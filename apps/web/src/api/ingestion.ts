import http from './http'
import { approvalMutationConfig, readExecutionApproval, type ExecutionApproval } from './executionApproval'
import { collectVisiblePages, type VisibleListPage, type VisibleListOptions } from './metadata'

export interface IngestionTask {
  id: number
  name: string
  sourceType: string
  sourceDatasourceId?: number
  targetDatasourceId?: number
  sourceSql?: string
  targetTable: string
  writeMode?: 'append' | 'replace'
  batchSize?: number
  scheduleCron?: string
  sourceConfig?: string
  status?: string
  lastRunStatus?: string
  lastRunAt?: string
  lastRunRows?: number
  lastError?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface IngestionTaskInput {
  name: string
  sourceType: string
  sourceDatasourceId: number
  targetDatasourceId: number
  sourceSql: string
  targetTable: string
  writeMode?: 'append' | 'replace'
  batchSize?: number
  scheduleCron?: string
  sourceConfig?: string
  status?: string
  description?: string
}

export function listTasks(): Promise<IngestionTask[]> {
  return collectVisiblePages((cursor, signal) => listTasksPage({ cursor, size: 200, signal }))
}

export function listTasksPage(options: VisibleListOptions = {}): Promise<VisibleListPage<IngestionTask>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<IngestionTask>>('/api/ingestion/tasks/page', { params: paging, signal }).then((res) => res.data)
}

export function createTask(payload: IngestionTaskInput): Promise<IngestionTask> {
  return http.post<IngestionTask>('/api/ingestion/tasks', payload).then((res) => res.data)
}

export function updateTask(id: number, payload: Partial<IngestionTaskInput>): Promise<IngestionTask> {
  return http.put<IngestionTask>(`/api/ingestion/tasks/${id}`, payload).then((res) => res.data)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`/api/ingestion/tasks/${id}`).then(() => undefined)
}

export interface IngestionRun {
  id: number
  taskId: number
  status: 'running' | 'success' | 'failed' | 'unknown'
  rowsRead?: number
  rowsWritten?: number
  durationMs?: number
  errorMessage?: string
  failureCode?: string
  requestKey?: string
  replayOfRunId?: number
  triggeredBy?: string
  startedAt: string
  finishedAt?: string
}

export function runTask(id: number, requestKey = crypto.randomUUID()): Promise<IngestionRun> {
  return http
    .post<IngestionRun>(`/api/ingestion/tasks/${id}/run`, undefined, {
      timeout: 3660000,
      headers: { 'Idempotency-Key': requestKey }
    })
    .then((res) => res.data)
}

export function replayRun(id: number, requestKey = crypto.randomUUID()): Promise<IngestionRun> {
  return http
    .post<IngestionRun>(`/api/ingestion/runs/${id}/replay`, undefined, {
      timeout: 3660000,
      headers: { 'Idempotency-Key': requestKey }
    })
    .then((res) => res.data)
}

export function listRuns(id: number, limit = 30): Promise<IngestionRun[]> {
  return listRunsPage(id, { size: Math.min(Math.max(limit, 1), 200) }).then((page) => page.items)
}

export function listRunsPage(id: number, options: VisibleListOptions = {}): Promise<VisibleListPage<IngestionRun>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<IngestionRun>>(`/api/ingestion/tasks/${id}/runs/page`, { params: paging, signal }).then((res) => res.data)
}

// —— 数据同步：跨任务的全局运行记录 ——

export interface SyncRunRecord {
  id: number
  taskId: number
  taskName: string
  status: 'running' | 'success' | 'failed' | 'unknown'
  rowsRead?: number
  rowsWritten?: number
  durationMs?: number
  errorMessage?: string
  failureCode?: string
  requestKey?: string
  replayOfRunId?: number
  triggeredBy?: string
  startedAt: string
  finishedAt?: string
}

export function listAllSyncRuns(taskId?: number, status?: string): Promise<SyncRunRecord[]> {
  return collectVisiblePages((cursor, signal) => listAllSyncRunsPage(taskId, status, { cursor, size: 200, signal }))
}

export function listAllSyncRunsPage(taskId?: number, status?: string, options: VisibleListOptions = {}): Promise<VisibleListPage<SyncRunRecord>> {
  const { signal, ...paging } = options
  return http.get<VisibleListPage<SyncRunRecord>>('/api/ingestion/runs/page', {
    params: { taskId: taskId || undefined, status: status || undefined, ...paging }, signal
  }).then((res) => res.data)
}

/** A fresh view-authorized definition, never a caller supplied approval snapshot. */
export function getTask(id: number): Promise<IngestionTask> {
  return http.get<IngestionTask>(`/api/ingestion/tasks/${id}`).then((res) => res.data)
}

export function getTaskApproval(id: number): Promise<ExecutionApproval> {
  return http.get<unknown>(`/api/ingestion/tasks/${id}/approval`).then((res) => readExecutionApproval(res.data))
}

export function approveTask(id: number, expectedRevision: number): Promise<ExecutionApproval> {
  return http.post<unknown>(`/api/ingestion/tasks/${id}/approve`, { expectedRevision }, approvalMutationConfig).then((res) => readExecutionApproval(res.data))
}

export function bindTaskCron(id: number, expectedRevision: number, expectedGeneration: number): Promise<ExecutionApproval> {
  return http.post<unknown>(`/api/ingestion/tasks/${id}/cron-binding`, { expectedRevision, expectedGeneration }, approvalMutationConfig).then((res) => readExecutionApproval(res.data))
}
