import http from './http'

export interface DataSource {
  id: number
  name: string
  type: string
  host: string
  port?: number
  databaseName?: string
  username?: string
  status?: string
  createdAt?: string
  updatedAt?: string
}

export interface DataSourceInput {
  name: string
  type: string
  host: string
  port?: number
  databaseName?: string
  username?: string
  status?: string
}

export function listDataSources(): Promise<DataSource[]> {
  return http.get<DataSource[]>('/api/metadata/datasources').then((res) => res.data)
}

export function createDataSource(payload: DataSourceInput): Promise<DataSource> {
  return http.post<DataSource>('/api/metadata/datasources', payload).then((res) => res.data)
}

export function updateDataSource(id: number, payload: DataSourceInput): Promise<DataSource> {
  return http.put<DataSource>(`/api/metadata/datasources/${id}`, payload).then((res) => res.data)
}

export function deleteDataSource(id: number): Promise<void> {
  return http.delete(`/api/metadata/datasources/${id}`).then(() => undefined)
}
