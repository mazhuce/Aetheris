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
  durationMs?: number
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

// —— 标签资产：数据表的业务标签 ——

export interface TableTagGroup {
  tag: string
  tableCount: number
  tables: { id: number; tableId: number; tableName: string; createdBy?: string; createdAt?: string }[]
}

export interface TableTagItem {
  id: number
  metadataTableId: number
  tagName: string
  createdBy?: string
  createdAt?: string
}

export function listTableTagGroups(): Promise<TableTagGroup[]> {
  return http.get<TableTagGroup[]>('/api/metadata/table-tags').then((res) => res.data)
}

export function addTableTag(tableId: number, tag: string): Promise<TableTagItem> {
  return http
    .post<TableTagItem>('/api/metadata/table-tags', { tableId, tag })
    .then((res) => res.data)
}

export function removeTableTag(id: number): Promise<void> {
  return http.delete(`/api/metadata/table-tags/${id}`).then(() => undefined)
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
  businessDomainId?: number
  layerSource?: 'manual' | 'auto'
  status?: string
  syncedAt?: string
  createdAt?: string
  updatedAt?: string
}

// —— 企业语义层：业务域与可复用维度 ——

export interface BusinessDomain {
  id: number
  domainCode: string
  domainName: string
  owner?: string
  status: 'active' | 'disabled'
  description?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export type BusinessDomainInput = Omit<BusinessDomain, 'id' | 'createdBy' | 'createdAt' | 'updatedAt'>

export function listBusinessDomains(keyword?: string, status?: string): Promise<BusinessDomain[]> {
  return http.get<BusinessDomain[]>('/api/metadata/business-domains', { params: { keyword, status } }).then((res) => res.data)
}
export function createBusinessDomain(payload: BusinessDomainInput): Promise<BusinessDomain> {
  return http.post<BusinessDomain>('/api/metadata/business-domains', payload).then((res) => res.data)
}
export function updateBusinessDomain(id: number, payload: BusinessDomainInput): Promise<BusinessDomain> {
  return http.put<BusinessDomain>(`/api/metadata/business-domains/${id}`, payload).then((res) => res.data)
}
export function deleteBusinessDomain(id: number): Promise<void> {
  return http.delete(`/api/metadata/business-domains/${id}`).then(() => undefined)
}

export interface SemanticVersion {
  id: number
  versionNo: number
  snapshot: Record<string, unknown>
  publishedBy: string
  publishedAt: string
}

export interface SemanticDimension {
  id: number
  dimensionCode: string
  dimensionName: string
  businessDomainId?: number
  metadataTableId: number
  columnName: string
  dimensionType: 'categorical' | 'time' | 'geography'
  status: 'draft' | 'published' | 'deprecated'
  version: number
  publishedVersion?: number
  owner?: string
  description?: string
  createdBy?: string
  publishedBy?: string
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

export type SemanticDimensionInput = Omit<SemanticDimension, 'id' | 'status' | 'version' | 'createdBy' | 'publishedBy' | 'publishedAt' | 'createdAt' | 'updatedAt'>

export function listSemanticDimensions(keyword?: string, status?: string, tableId?: number): Promise<SemanticDimension[]> {
  return http.get<SemanticDimension[]>('/api/metadata/semantic-dimensions', { params: { keyword, status, tableId } }).then((res) => res.data)
}
export function createSemanticDimension(payload: SemanticDimensionInput): Promise<SemanticDimension> {
  return http.post<SemanticDimension>('/api/metadata/semantic-dimensions', payload).then((res) => res.data)
}
export function updateSemanticDimension(id: number, payload: SemanticDimensionInput): Promise<SemanticDimension> {
  return http.put<SemanticDimension>(`/api/metadata/semantic-dimensions/${id}`, payload).then((res) => res.data)
}
export function deleteSemanticDimension(id: number): Promise<void> {
  return http.delete(`/api/metadata/semantic-dimensions/${id}`).then(() => undefined)
}
export function validateSemanticDimension(id: number): Promise<{ valid: boolean; message: string }> {
  return http.post(`/api/metadata/semantic-dimensions/${id}/validate`).then((res) => res.data)
}
export function publishSemanticDimension(id: number): Promise<SemanticDimension> {
  return http.post<SemanticDimension>(`/api/metadata/semantic-dimensions/${id}/publish`).then((res) => res.data)
}
export function newSemanticDimensionVersion(id: number): Promise<SemanticDimension> {
  return http.post<SemanticDimension>(`/api/metadata/semantic-dimensions/${id}/new-version`).then((res) => res.data)
}
export function discardSemanticDimensionDraft(id: number): Promise<SemanticDimension> {
  return http.post<SemanticDimension>(`/api/metadata/semantic-dimensions/${id}/discard-draft`).then((res) => res.data)
}
export function deprecateSemanticDimension(id: number): Promise<SemanticDimension> {
  return http.post<SemanticDimension>(`/api/metadata/semantic-dimensions/${id}/deprecate`).then((res) => res.data)
}
export function listSemanticDimensionVersions(id: number): Promise<SemanticVersion[]> {
  return http.get<SemanticVersion[]>(`/api/metadata/semantic-dimensions/${id}/versions`).then((res) => res.data)
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
  layer?: WarehouseLayer
  layerSource?: 'manual' | 'auto'
  domain?: string
  businessDomainId?: number
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

// —— 指标定义：业务指标的计算口径与版本 ——

export interface MetricDefinition {
  id: number
  metricCode: string
  metricName: string
  businessDomain?: string
  businessDomainId?: number
  logicalModelId?: number
  sourceTableId?: number
  layer?: 'atomic' | 'derived' | 'composite'
  calibreFormula?: string
  aggregation?: 'SUM' | 'COUNT' | 'COUNT_DISTINCT' | 'AVG' | 'MIN' | 'MAX'
  measureColumn?: string
  filtersJson?: string
  timeColumn?: string
  defaultTimeGrain?: 'none' | 'day' | 'month' | 'year'
  dimensionIdsJson?: string
  unit?: string
  owner?: string
  status?: 'draft' | 'published' | 'deprecated'
  version?: number
  publishedVersion?: number
  description?: string
  createdBy?: string
  publishedBy?: string
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface MetricDefinitionInput {
  metricCode: string
  metricName: string
  businessDomain?: string
  businessDomainId?: number
  logicalModelId?: number
  sourceTableId?: number
  layer?: string
  calibreFormula?: string
  aggregation?: string
  measureColumn?: string
  filtersJson?: string
  timeColumn?: string
  defaultTimeGrain?: string
  dimensionIdsJson?: string
  unit?: string
  owner?: string
  status?: string
  version?: number
  description?: string
}

export function listMetricDefinitions(keyword?: string): Promise<MetricDefinition[]> {
  return http
    .get<MetricDefinition[]>('/api/metadata/metric-definitions', { params: keyword ? { keyword } : {} })
    .then((res) => res.data)
}

export function listPublishedMetrics(keyword?: string): Promise<MetricDefinition[]> {
  return http.get<MetricDefinition[]>('/api/metadata/semantic/metrics', { params: { keyword } }).then((res) => res.data)
}

export interface MetricQueryInput {
  dimensions?: string[]
  timeGrain?: 'none' | 'day' | 'month' | 'year'
  startDate?: string
  endDate?: string
  limit?: number
}

export interface SemanticMetricQueryResult extends QueryResult {
  metricCode: string
  metricName: string
  version: number
  publishedVersion?: number
  unit?: string
  dimensions: string[]
  timeGrain: string
  generatedSql: string
}

export function validateMetricDefinition(id: number): Promise<{ valid: boolean; message: string }> {
  return http.post(`/api/metadata/metric-definitions/${id}/validate`).then((res) => res.data)
}
export function previewMetricDefinition(id: number, input: MetricQueryInput = {}): Promise<SemanticMetricQueryResult> {
  return http.post<SemanticMetricQueryResult>(`/api/metadata/metric-definitions/${id}/preview`, input, { timeout: 50000 }).then((res) => res.data)
}
export function querySemanticMetric(code: string, input: MetricQueryInput = {}): Promise<SemanticMetricQueryResult> {
  return http.post<SemanticMetricQueryResult>(`/api/metadata/semantic/metrics/${code}/query`, input, { timeout: 50000 }).then((res) => res.data)
}
export function publishMetricDefinition(id: number): Promise<MetricDefinition> {
  return http.post<MetricDefinition>(`/api/metadata/metric-definitions/${id}/publish`).then((res) => res.data)
}
export function newMetricDefinitionVersion(id: number): Promise<MetricDefinition> {
  return http.post<MetricDefinition>(`/api/metadata/metric-definitions/${id}/new-version`).then((res) => res.data)
}
export function discardMetricDefinitionDraft(id: number): Promise<MetricDefinition> {
  return http.post<MetricDefinition>(`/api/metadata/metric-definitions/${id}/discard-draft`).then((res) => res.data)
}
export function deprecateMetricDefinition(id: number): Promise<MetricDefinition> {
  return http.post<MetricDefinition>(`/api/metadata/metric-definitions/${id}/deprecate`).then((res) => res.data)
}
export function listMetricDefinitionVersions(id: number): Promise<SemanticVersion[]> {
  return http.get<SemanticVersion[]>(`/api/metadata/metric-definitions/${id}/versions`).then((res) => res.data)
}

export function createMetricDefinition(payload: MetricDefinitionInput): Promise<MetricDefinition> {
  return http.post<MetricDefinition>('/api/metadata/metric-definitions', payload).then((res) => res.data)
}

export function updateMetricDefinition(id: number, payload: MetricDefinitionInput): Promise<MetricDefinition> {
  return http.put<MetricDefinition>(`/api/metadata/metric-definitions/${id}`, payload).then((res) => res.data)
}

export function deleteMetricDefinition(id: number): Promise<void> {
  return http.delete(`/api/metadata/metric-definitions/${id}`).then(() => undefined)
}

// —— 逻辑模型：面向业务的概念与逻辑模型设计 ——

export interface LogicalModel {
  id: number
  modelCode: string
  modelName: string
  businessDomain?: string
  businessDomainId?: number
  modelType?: 'conceptual' | 'relational' | 'dimensional'
  entitiesJson?: unknown
  status?: 'draft' | 'published' | 'deprecated'
  version?: number
  publishedVersion?: number
  description?: string
  owner?: string
  createdBy?: string
  publishedBy?: string
  publishedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface LogicalModelInput {
  modelCode: string
  modelName: string
  businessDomain?: string
  businessDomainId?: number
  modelType?: string
  status?: string
  version?: number
  description?: string
  owner?: string
  entitiesJson?: string
}

export function listLogicalModels(keyword?: string, status?: string): Promise<LogicalModel[]> {
  return http
    .get<LogicalModel[]>('/api/metadata/logical-models', { params: { keyword, status } })
    .then((res) => res.data)
}

export function createLogicalModel(payload: LogicalModelInput): Promise<LogicalModel> {
  return http.post<LogicalModel>('/api/metadata/logical-models', payload).then((res) => res.data)
}

export function updateLogicalModel(id: number, payload: LogicalModelInput): Promise<LogicalModel> {
  return http.put<LogicalModel>(`/api/metadata/logical-models/${id}`, payload).then((res) => res.data)
}

export function deleteLogicalModel(id: number): Promise<void> {
  return http.delete(`/api/metadata/logical-models/${id}`).then(() => undefined)
}

export function validateLogicalModel(id: number): Promise<{ valid: boolean; message: string; entities: number; relations: number }> {
  return http.post(`/api/metadata/logical-models/${id}/validate`).then((res) => res.data)
}
export function publishLogicalModel(id: number): Promise<LogicalModel> {
  return http.post<LogicalModel>(`/api/metadata/logical-models/${id}/publish`).then((res) => res.data)
}
export function newLogicalModelVersion(id: number): Promise<LogicalModel> {
  return http.post<LogicalModel>(`/api/metadata/logical-models/${id}/new-version`).then((res) => res.data)
}
export function discardLogicalModelDraft(id: number): Promise<LogicalModel> {
  return http.post<LogicalModel>(`/api/metadata/logical-models/${id}/discard-draft`).then((res) => res.data)
}
export function deprecateLogicalModel(id: number): Promise<LogicalModel> {
  return http.post<LogicalModel>(`/api/metadata/logical-models/${id}/deprecate`).then((res) => res.data)
}
export function listLogicalModelVersions(id: number): Promise<SemanticVersion[]> {
  return http.get<SemanticVersion[]>(`/api/metadata/logical-models/${id}/versions`).then((res) => res.data)
}

// —— API 资产：可复用数据 API 的集中登记 ——

export interface ApiAsset {
  id: number
  assetCode: string
  assetName: string
  apiPath: string
  httpMethod?: string
  category?: string
  owner?: string
  status?: 'registered' | 'published' | 'deprecated'
  version?: string
  description?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface ApiAssetInput {
  assetCode: string
  assetName: string
  apiPath: string
  httpMethod?: string
  category?: string
  owner?: string
  status?: string
  version?: string
  description?: string
}

export function listApiAssets(keyword?: string): Promise<ApiAsset[]> {
  return http
    .get<ApiAsset[]>('/api/metadata/api-assets', { params: keyword ? { keyword } : {} })
    .then((res) => res.data)
}

export function createApiAsset(payload: ApiAssetInput): Promise<ApiAsset> {
  return http.post<ApiAsset>('/api/metadata/api-assets', payload).then((res) => res.data)
}

export function updateApiAsset(id: number, payload: ApiAssetInput): Promise<ApiAsset> {
  return http.put<ApiAsset>(`/api/metadata/api-assets/${id}`, payload).then((res) => res.data)
}

export function deleteApiAsset(id: number): Promise<void> {
  return http.delete(`/api/metadata/api-assets/${id}`).then(() => undefined)
}

// —— 数据产品：面向业务交付的数据产品及其生命周期 ——

export interface DataProduct {
  id: number
  productCode: string
  productName: string
  category?: string
  lifecycle?: 'planning' | 'developing' | 'released' | 'offline'
  owner?: string
  consumers?: string
  sla?: string
  description?: string
  createdBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface DataProductInput {
  productCode: string
  productName: string
  category?: string
  lifecycle?: string
  owner?: string
  consumers?: string
  sla?: string
  description?: string
}

export function listDataProducts(keyword?: string): Promise<DataProduct[]> {
  return http
    .get<DataProduct[]>('/api/metadata/data-products', { params: keyword ? { keyword } : {} })
    .then((res) => res.data)
}

export function createDataProduct(payload: DataProductInput): Promise<DataProduct> {
  return http.post<DataProduct>('/api/metadata/data-products', payload).then((res) => res.data)
}

export function updateDataProduct(id: number, payload: DataProductInput): Promise<DataProduct> {
  return http.put<DataProduct>(`/api/metadata/data-products/${id}`, payload).then((res) => res.data)
}

export function deleteDataProduct(id: number): Promise<void> {
  return http.delete(`/api/metadata/data-products/${id}`).then(() => undefined)
}
