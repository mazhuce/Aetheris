import http from './http'

export interface DataSource {
  id: number
  name: string
  type: string
  host: string
  port?: number
  databaseName?: string
  username?: string
  password?: string
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
  password?: string
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

export interface TestResult {
  success: boolean
  message: string
  latencyMs?: number
}

export interface QueryResult {
  columns: string[]
  rows: unknown[][]
  rowCount: number
  truncated: boolean
}

export function testDataSource(id: number): Promise<TestResult> {
  return http.post<TestResult>(`/api/metadata/datasources/${id}/test`).then((res) => res.data)
}

export function queryDataSource(id: number, sql: string): Promise<QueryResult> {
  return http.post<QueryResult>(`/api/metadata/datasources/${id}/query`, { sql }).then((res) => res.data)
}

export interface MetadataTable {
  id: number
  datasourceName: string
  schemaName?: string
  tableName: string
  tableComment?: string
  owner?: string
  rowCount?: number
  status?: string
  createdAt?: string
  updatedAt?: string
}

export interface MetadataTableInput {
  datasourceName: string
  schemaName?: string
  tableName: string
  tableComment?: string
  owner?: string
  rowCount?: number
  status?: string
}

export interface LineageRelation {
  id: number
  sourceTable: string
  targetTable: string
  relationType?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface LineageRelationInput {
  sourceTable: string
  targetTable: string
  relationType?: string
  description?: string
}

export function listTables(): Promise<MetadataTable[]> {
  return http.get<MetadataTable[]>('/api/metadata/tables').then((res) => res.data)
}

export function createTable(payload: MetadataTableInput): Promise<MetadataTable> {
  return http.post<MetadataTable>('/api/metadata/tables', payload).then((res) => res.data)
}

export function updateTable(id: number, payload: MetadataTableInput): Promise<MetadataTable> {
  return http.put<MetadataTable>(`/api/metadata/tables/${id}`, payload).then((res) => res.data)
}

export function deleteTable(id: number): Promise<void> {
  return http.delete(`/api/metadata/tables/${id}`).then(() => undefined)
}

export function listLineages(): Promise<LineageRelation[]> {
  return http.get<LineageRelation[]>('/api/metadata/lineages').then((res) => res.data)
}

export function createLineage(payload: LineageRelationInput): Promise<LineageRelation> {
  return http.post<LineageRelation>('/api/metadata/lineages', payload).then((res) => res.data)
}

export function updateLineage(id: number, payload: LineageRelationInput): Promise<LineageRelation> {
  return http.put<LineageRelation>(`/api/metadata/lineages/${id}`, payload).then((res) => res.data)
}

export function deleteLineage(id: number): Promise<void> {
  return http.delete(`/api/metadata/lineages/${id}`).then(() => undefined)
}
