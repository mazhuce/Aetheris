import http from './http'

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
  return http.get<IngestionTask[]>('/api/ingestion/tasks').then((res) => res.data)
}

export function createTask(payload: IngestionTaskInput): Promise<IngestionTask> {
  return http.post<IngestionTask>('/api/ingestion/tasks', payload).then((res) => res.data)
}

export function updateTask(id: number, payload: IngestionTaskInput): Promise<IngestionTask> {
  return http.put<IngestionTask>(`/api/ingestion/tasks/${id}`, payload).then((res) => res.data)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`/api/ingestion/tasks/${id}`).then(() => undefined)
}

export interface IngestionRun {
  id: number
  taskId: number
  status: 'running' | 'success' | 'failed'
  rowsRead?: number
  rowsWritten?: number
  durationMs?: number
  errorMessage?: string
  triggeredBy?: string
  startedAt: string
  finishedAt?: string
}

export function runTask(id: number): Promise<IngestionRun> {
  return http.post<IngestionRun>(`/api/ingestion/tasks/${id}/run`, undefined, { timeout: 3660000 }).then((res) => res.data)
}

export function listRuns(id: number, limit = 30): Promise<IngestionRun[]> {
  return http.get<IngestionRun[]>(`/api/ingestion/tasks/${id}/runs`, { params: { limit } }).then((res) => res.data)
}
