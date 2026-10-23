import http from './http'

export type GovernedResource = 'workflow_task' | 'ingestion_task' | 'quality_rule' | 'governance_policy' | 'data_standard' | 'dataset' | 'dashboard'
export type ResourceAction = 'update' | 'delete' | 'execute' | 'publish' | 'query'

export async function getMyResourcePermissions(resourceType: GovernedResource, action: ResourceAction, ids: number[]): Promise<number[]> {
  const response = await http.get<{ allowedIds: number[] }>('/api/auth/me/resource-permissions', {
    params: { resourceType, action, ids: ids.join(',') }
  })
  return Array.isArray(response.data.allowedIds) ? response.data.allowedIds : []
}
