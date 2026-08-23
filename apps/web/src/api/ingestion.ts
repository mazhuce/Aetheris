import http from './http'

export interface IngestionTask {
  id: number
  name: string
  sourceType: string
  targetTable: string
  scheduleCron?: string
  sourceConfig?: string
  status?: string
  lastRunStatus?: string
  lastRunAt?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface IngestionTaskInput {
  name: string
  sourceType: string
  targetTable: string
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

export function runTask(id: number): Promise<IngestionTask> {
  return http.post<IngestionTask>(`/api/ingestion/tasks/${id}/run`).then((res) => res.data)
}
