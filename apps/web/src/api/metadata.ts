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
  managed?: boolean
  jdbcUrl?: string
  connectionParams?: string
  description?: string
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
  jdbcUrl?: string
  connectionParams?: string
  description?: string
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
  databaseProduct?: string
  databaseVersion?: string
  driverName?: string
  driverVersion?: string
}

export interface QueryResult {
  columns: string[]
  rows: unknown[][]
  rowCount: number
  truncated: boolean
}

export function testDataSource(id: number): Promise<TestResult> {
  return http.post<TestResult>(`/api/metadata/datasources/${id}/test`, undefined, { timeout: 40000 }).then((res) => res.data)
}

export interface AdhocQueryResult {
  columns: string[]
  rows: unknown[][]
  rowCount: number
  truncated: boolean
  durationMs?: number
}

export function runAdhocQuery(datasourceId: number, sql: string): Promise<AdhocQueryResult> {
  return http
    .post<AdhocQueryResult>('/api/metadata/query', { datasourceId, sql }, { timeout: 70000 })
    .then((res) => res.data)
}

export function testDataSourceConfig(payload: DataSourceInput): Promise<TestResult> {
  return http.post<TestResult>('/api/metadata/datasources/test', payload, { timeout: 40000 }).then((res) => res.data)
}

export interface DataSourceSchema {
  name: string
}

export interface DataSourceTable {
  name: string
  type?: string
  remarks?: string
}

export interface DataSourceColumn {
  name: string
  type: string
  size?: number
  nullable?: boolean
  ordinal?: number
  remarks?: string
}

export function listDataSourceSchemas(id: number): Promise<DataSourceSchema[]> {
  return http.get<DataSourceSchema[]>(`/api/metadata/datasources/${id}/schemas`).then((res) => res.data)
}

export function listDataSourceTables(id: number, schema: string): Promise<DataSourceTable[]> {
  return http.get<DataSourceTable[]>(`/api/metadata/datasources/${id}/tables`, { params: { schema } }).then((res) => res.data)
}

export function listDataSourceColumns(id: number, schema: string, table: string): Promise<DataSourceColumn[]> {
  return http.get<DataSourceColumn[]>(`/api/metadata/datasources/${id}/columns`, { params: { schema, table } }).then((res) => res.data)
}

export function previewDataSourceTable(id: number, schema: string, table: string): Promise<QueryResult> {
  return http.post<QueryResult>(`/api/metadata/datasources/${id}/preview`, { schema, table }, { timeout: 45000 }).then((res) => res.data)
}

export function queryDataSource(id: number, sql: string): Promise<QueryResult> {
  return http.post<QueryResult>(`/api/metadata/datasources/${id}/query`, { sql }, { timeout: 45000 }).then((res) => res.data)
}

export type WarehouseLayer = 'ods' | 'dwd' | 'dws' | 'ads' | 'dim' | 'other'

export interface MetadataTable {
  id: number
  datasourceId?: number
  datasourceName: string
  schemaName?: string
  tableName: string
  tableComment?: string
  tableType?: string
  columnCount?: number
  owner?: string
  rowCount?: number
  source?: 'manual' | 'auto'
  layer?: WarehouseLayer
  domain?: string
  layerSource?: 'manual' | 'auto'
  status?: string
  syncedAt?: string
  createdAt?: string
  updatedAt?: string
}

/** 层级中文名与展示顺序，顺序即数据流向；与后端 WarehouseMapService 保持一致。 */
export const WAREHOUSE_LAYERS: { value: WarehouseLayer; label: string }[] = [
  { value: 'ods', label: '贴源层' },
  { value: 'dwd', label: '明细层' },
  { value: 'dws', label: '汇总层' },
  { value: 'ads', label: '应用层' },
  { value: 'dim', label: '维度层' },
  { value: 'other', label: '未分层' }
]

export function warehouseLayerLabel(layer?: string): string {
  return WAREHOUSE_LAYERS.find((item) => item.value === layer)?.label ?? '未分层'
}

export interface WarehouseTableBrief {
  id: number
  schemaName?: string
  tableName: string
  tableComment?: string
  columnCount?: number
  status?: string
}

export interface WarehouseLayerSummary {
  layer: WarehouseLayer
  label: string
  tableCount: number
  schemas: string[]
  tables: WarehouseTableBrief[]
}

export interface WarehouseMap {
  layers: WarehouseLayerSummary[]
  total: number
}

export function getWarehouseMap(datasourceName?: string, includeStale = false): Promise<WarehouseMap> {
  return http
    .get<WarehouseMap>('/api/metadata/tables/warehouse-map', {
      params: { datasourceName: datasourceName || undefined, includeStale }
    })
    .then((res) => res.data)
}

export interface MetadataSyncResult {
  datasourceName: string
  schemas: string[]
  scanned: number
  created: number
  updated: number
  removed: number
  skipped: number
  failures: string[]
}

export function syncMetadataTables(datasourceId: number, schemaName?: string): Promise<MetadataSyncResult> {
  // 采集需遍历目标库全部表，耗时明显高于普通接口。
  return http
    .post<MetadataSyncResult>('/api/metadata/tables/sync', { datasourceId, schemaName }, { timeout: 120000 })
    .then((res) => res.data)
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
  /** manual=人工登记，parsed=从任务 SQL 解析；解析只覆盖 parsed 记录。 */
  source?: 'manual' | 'parsed'
  createdAt?: string
  updatedAt?: string
}

export interface LineageParseResult {
  statements: number
  created: number
  replaced: number
  skipped: number
  edges: string[]
}

export function parseLineage(sql: string, relationType?: string): Promise<LineageParseResult> {
  // 解析可能包含整套加工脚本，比普通接口耗时长。
  return http
    .post<LineageParseResult>('/api/metadata/lineages/parse', { sql, relationType }, { timeout: 90000 })
    .then((res) => res.data)
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

/** 全局搜索：匹配表名/表注释/业务域，供控制台顶部搜索框使用。 */
export function searchTables(keyword: string, limit = 10): Promise<MetadataTable[]> {
  return http
    .get<MetadataTable[]>('/api/metadata/tables/search', { params: { keyword, limit } })
    .then((res) => res.data)
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

// —— 查询历史：即席查询执行记录，按用户隔离 ——

export interface QueryHistoryRecord {
  id: number
  username: string
  datasourceId: number
  datasourceName?: string
  sqlText: string
  status: 'success' | 'failed'
  rowCount?: number
  durationMs?: number
  errorMessage?: string
  createdAt?: string
}

export function listQueryHistory(status?: string): Promise<QueryHistoryRecord[]> {
  return http
    .get<QueryHistoryRecord[]>('/api/metadata/query-history', { params: status ? { status } : {} })
    .then((res) => res.data)
}

export function deleteQueryHistory(id: number): Promise<void> {
  return http.delete(`/api/metadata/query-history/${id}`).then(() => undefined)
}

export function clearQueryHistory(): Promise<number> {
  return http.delete<{ deleted: number }>('/api/metadata/query-history').then((res) => res.data.deleted)
}
