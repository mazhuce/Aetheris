import http from './http'

export interface AnalyticsDataset {
  id: number
  name: string
  description?: string
  sourceType: 'sql' | 'metric'
  datasourceId?: number
  datasourceName?: string
  querySql?: string
  metricCode?: string
  metricQuery?: {
    dimensions?: string[]
    timeGrain?: 'none' | 'day' | 'month' | 'year'
    startDate?: string
    endDate?: string
    limit?: number
  }
  schema?: { columns?: string[] }
  refreshSeconds: number
  status: 'draft' | 'published' | 'deprecated'
  createdBy: string
  createdAt: string
  updatedAt: string
}

export interface DatasetInput {
  name: string
  description?: string
  sourceType: 'sql' | 'metric'
  datasourceId?: number
  datasourceName?: string
  querySql?: string
  metricCode?: string
  metricQuery?: AnalyticsDataset['metricQuery']
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

export function listAnalyticsDatasets(signal?: AbortSignal): Promise<AnalyticsDataset[]> {
  return http.get<AnalyticsDataset[]>('/api/analytics/datasets', { signal }).then((response) => response.data)
}

export function createAnalyticsDataset(input: DatasetInput): Promise<AnalyticsDataset> {
  return http.post<AnalyticsDataset>('/api/analytics/datasets', input).then((response) => response.data)
}

export function updateAnalyticsDataset(id: number, input: DatasetInput): Promise<AnalyticsDataset> {
  return http.put<AnalyticsDataset>(`/api/analytics/datasets/${id}`, input).then((response) => response.data)
}

export function publishAnalyticsDataset(id: number): Promise<AnalyticsDataset> {
  return http.post<AnalyticsDataset>(`/api/analytics/datasets/${id}/publish`).then((response) => response.data)
}

export function deleteAnalyticsDataset(id: number): Promise<void> {
  return http.delete(`/api/analytics/datasets/${id}`).then(() => undefined)
}

export function queryAnalyticsDataset(id: number, refresh = false, signal?: AbortSignal): Promise<AnalyticsQueryResult> {
  return http
    .post<AnalyticsQueryResult>(`/api/analytics/datasets/${id}/query`, undefined, {
      params: { refresh },
      signal,
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

export function getAnalyticsDashboard(id: number, signal?: AbortSignal): Promise<AnalyticsDashboard> {
  return http.get<AnalyticsDashboard>(`/api/analytics/dashboards/${id}`, { signal }).then((response) => response.data)
}

export function getPublishedDashboard(slug: string, signal?: AbortSignal): Promise<AnalyticsDashboard> {
  return http.get<AnalyticsDashboard>(`/api/analytics/dashboards/published/${slug}`, { signal }).then((response) => response.data)
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
  input: { name: string; description?: string; spec: DashboardSpec },
  signal?: AbortSignal
): Promise<AnalyticsDashboard> {
  return http.put<AnalyticsDashboard>(`/api/analytics/dashboards/${id}`, input, { signal }).then((response) => response.data)
}

export function publishAnalyticsDashboard(id: number, signal?: AbortSignal): Promise<AnalyticsDashboard> {
  return http.post<AnalyticsDashboard>(`/api/analytics/dashboards/${id}/publish`, undefined, { signal }).then((response) => response.data)
}

export function deleteAnalyticsDashboard(id: number): Promise<void> {
  return http.delete(`/api/analytics/dashboards/${id}`).then(() => undefined)
}
