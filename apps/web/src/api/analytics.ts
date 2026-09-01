import http from './http'

export interface AnalyticsDataset {
  id: number
  name: string
  description?: string
  datasourceId: number
  datasourceName?: string
  querySql: string
  schema?: { columns?: string[] }
  refreshSeconds: number
  status: string
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface DatasetInput {
  name: string
  description?: string
  datasourceId: number
  datasourceName?: string
  querySql: string
  refreshSeconds: number
}

export interface AnalyticsQueryResult {
  columns: string[]
  rows: unknown[][]
  rowCount: number
  truncated?: boolean
}

export type DashboardWidgetType = 'metric' | 'line' | 'bar' | 'pie' | 'table' | 'text'

export interface DashboardLayoutItem {
  i: string
  x: number
  y: number
  w: number
  h: number
  minW?: number
  minH?: number
}

export interface DashboardWidgetMapping {
  xField?: string
  yField?: string
  valueField?: string
  categoryField?: string
}

export interface DashboardWidget {
  id: string
  type: DashboardWidgetType
  title: string
  datasetId?: number
  mapping: DashboardWidgetMapping
  refreshSeconds?: number
  decimals?: number
  content?: string
}

export interface DashboardSpec {
  schemaVersion: number
  theme: 'dark'
  layouts: Record<string, DashboardLayoutItem[]>
  widgets: DashboardWidget[]
  globalFilters: unknown[]
}

export interface AnalyticsDashboard {
  id: number
  name: string
  description?: string
  slug: string
  status: 'draft' | 'published'
  draftSpec: DashboardSpec
  publishedSpec?: DashboardSpec
  publishedVersion: number
  createdBy: string
  updatedBy: string
  createdAt: string
  updatedAt: string
  publishedAt?: string
}

export interface DashboardTemplate {
  id: number
  code: string
  name: string
  description: string
  category: string
  previewTone: string
  spec: DashboardSpec
  sortOrder: number
}

export function listAnalyticsDatasets(): Promise<AnalyticsDataset[]> {
  return http.get<AnalyticsDataset[]>('/api/analytics/datasets').then((response) => response.data)
}

export function createAnalyticsDataset(input: DatasetInput): Promise<AnalyticsDataset> {
  return http.post<AnalyticsDataset>('/api/analytics/datasets', input).then((response) => response.data)
}

export function updateAnalyticsDataset(id: number, input: DatasetInput): Promise<AnalyticsDataset> {
  return http.put<AnalyticsDataset>(`/api/analytics/datasets/${id}`, input).then((response) => response.data)
}

export function deleteAnalyticsDataset(id: number): Promise<void> {
  return http.delete(`/api/analytics/datasets/${id}`).then(() => undefined)
}

export function queryAnalyticsDataset(id: number, refresh = false): Promise<AnalyticsQueryResult> {
  return http
    .post<AnalyticsQueryResult>(`/api/analytics/datasets/${id}/query`, undefined, {
      params: { refresh },
      timeout: 50000
    })
    .then((response) => response.data)
}

export function listDashboardTemplates(): Promise<DashboardTemplate[]> {
  return http.get<DashboardTemplate[]>('/api/analytics/dashboard-templates').then((response) => response.data)
}

export function listAnalyticsDashboards(): Promise<AnalyticsDashboard[]> {
  return http.get<AnalyticsDashboard[]>('/api/analytics/dashboards').then((response) => response.data)
}

export function getAnalyticsDashboard(id: number): Promise<AnalyticsDashboard> {
  return http.get<AnalyticsDashboard>(`/api/analytics/dashboards/${id}`).then((response) => response.data)
}

export function getPublishedDashboard(slug: string): Promise<AnalyticsDashboard> {
  return http.get<AnalyticsDashboard>(`/api/analytics/dashboards/published/${slug}`).then((response) => response.data)
}

export function createAnalyticsDashboard(input: {
  name: string
  description?: string
  templateId?: number
}): Promise<AnalyticsDashboard> {
  return http.post<AnalyticsDashboard>('/api/analytics/dashboards', input).then((response) => response.data)
}

export function updateAnalyticsDashboard(
  id: number,
  input: { name: string; description?: string; spec: DashboardSpec }
): Promise<AnalyticsDashboard> {
  return http.put<AnalyticsDashboard>(`/api/analytics/dashboards/${id}`, input).then((response) => response.data)
}

export function publishAnalyticsDashboard(id: number): Promise<AnalyticsDashboard> {
  return http.post<AnalyticsDashboard>(`/api/analytics/dashboards/${id}/publish`).then((response) => response.data)
}

export function deleteAnalyticsDashboard(id: number): Promise<void> {
  return http.delete(`/api/analytics/dashboards/${id}`).then(() => undefined)
}
