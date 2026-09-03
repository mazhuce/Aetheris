import http from './http'

// —— 统一资源权限模型（auth-service /api/auth/rbac）——
// 以下端点全部要求系统管理权限（service.admin.manage），由后端决策引擎判定，
// 前端按钮可见性只是体验优化，不构成安全边界。

export interface RbacRole {
  id: number
  code: string
  name: string
  description?: string
  isSystem?: boolean
  createdAt?: string
  updatedAt?: string
}

export interface RbacRoleInput {
  code: string
  name: string
  description?: string
}

export interface RbacPermission {
  id: number
  code: string
  resourceType: string
  action: string
  name: string
  description?: string
}

export interface UserGroup {
  id: number
  code: string
  name: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface UserGroupInput {
  code: string
  name: string
  description?: string
}

export interface GroupMember {
  userId: number
  username: string
  nickname?: string
}

export interface Organization {
  id: number
  code: string
  name: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface OrganizationInput {
  code: string
  name: string
  description?: string
}

export interface Workspace {
  id: number
  organizationId: number
  code: string
  name: string
  description?: string
  status: 'active' | 'disabled'
  createdAt?: string
  updatedAt?: string
}

export interface WorkspaceInput {
  organizationId: number
  code: string
  name: string
  description?: string
  status: 'active' | 'disabled'
}

/** 数据范围：kind 表达层级（workspace/domain/table/column/metric…），values 为字符串集合。 */
export interface DataScope {
  kind: string
  values: string[]
}

export type GranteeType = 'user' | 'group' | 'role'

export interface Grant {
  id: number
  granteeType: GranteeType
  granteeId: number
  granteeName?: string
  resourceType: string
  resourceId?: number | null
  dataScope?: DataScope | null
  actions: string[]
  enabled?: boolean
  grantedBy?: string
  createdAt?: string
  updatedAt?: string
}

export interface GrantInput {
  granteeType: GranteeType
  granteeId: number
  resourceType: string
  resourceId?: number | null
  dataScope?: DataScope | null
  actions: string[]
  enabled?: boolean
}

export interface GrantUpdateInput {
  dataScope?: DataScope | null
  actions: string[]
  enabled?: boolean
}

export interface GrantQuery {
  granteeType?: string
  resourceType?: string
  resourceId?: number
}

// —— 角色 ——

export function listRoles(): Promise<RbacRole[]> {
  return http.get<RbacRole[]>('/api/auth/rbac/roles').then((res) => res.data)
}

export function createRole(payload: RbacRoleInput): Promise<RbacRole> {
  return http.post<RbacRole>('/api/auth/rbac/roles', payload).then((res) => res.data)
}

export function updateRole(id: number, payload: RbacRoleInput): Promise<RbacRole> {
  return http.put<RbacRole>(`/api/auth/rbac/roles/${id}`, payload).then((res) => res.data)
}

export function deleteRole(id: number): Promise<void> {
  return http.delete(`/api/auth/rbac/roles/${id}`).then(() => undefined)
}

export function listRolePermissionCodes(id: number): Promise<string[]> {
  return http.get<string[]>(`/api/auth/rbac/roles/${id}/permission-codes`).then((res) => res.data)
}

export function bindRolePermissions(id: number, codes: string[]): Promise<void> {
  return http.put(`/api/auth/rbac/roles/${id}/permissions`, { codes }).then(() => undefined)
}

// —— 权限目录 ——

export function listPermissions(): Promise<RbacPermission[]> {
  return http.get<RbacPermission[]>('/api/auth/rbac/permissions').then((res) => res.data)
}

// —— 用户组 ——

export function listGroups(): Promise<UserGroup[]> {
  return http.get<UserGroup[]>('/api/auth/rbac/groups').then((res) => res.data)
}

export function createGroup(payload: UserGroupInput): Promise<UserGroup> {
  return http.post<UserGroup>('/api/auth/rbac/groups', payload).then((res) => res.data)
}

export function updateGroup(id: number, payload: UserGroupInput): Promise<UserGroup> {
  return http.put<UserGroup>(`/api/auth/rbac/groups/${id}`, payload).then((res) => res.data)
}

export function deleteGroup(id: number): Promise<void> {
  return http.delete(`/api/auth/rbac/groups/${id}`).then(() => undefined)
}

export function listGroupMembers(id: number): Promise<GroupMember[]> {
  return http.get<GroupMember[]>(`/api/auth/rbac/groups/${id}/members`).then((res) => res.data)
}

export function addGroupMembers(id: number, userIds: number[]): Promise<void> {
  return http.post(`/api/auth/rbac/groups/${id}/members`, { userIds }).then(() => undefined)
}

export function removeGroupMember(groupId: number, userId: number): Promise<void> {
  return http.delete(`/api/auth/rbac/groups/${groupId}/members/${userId}`).then(() => undefined)
}

// —— 用户角色绑定 ——

export function listUserRoleCodes(userId: number): Promise<string[]> {
  return http.get<string[]>(`/api/auth/rbac/users/${userId}/roles`).then((res) => res.data)
}

export function bindUserRoles(userId: number, roleCodes: string[]): Promise<void> {
  return http.put(`/api/auth/rbac/users/${userId}/roles`, { roleCodes }).then(() => undefined)
}

// —— 组织与项目空间 ——

export function listOrganizations(): Promise<Organization[]> {
  return http.get<Organization[]>('/api/auth/rbac/organizations').then((res) => res.data)
}

export function createOrganization(payload: OrganizationInput): Promise<Organization> {
  return http.post<Organization>('/api/auth/rbac/organizations', payload).then((res) => res.data)
}

export function updateOrganization(id: number, payload: OrganizationInput): Promise<Organization> {
  return http.put<Organization>(`/api/auth/rbac/organizations/${id}`, payload).then((res) => res.data)
}

export function deleteOrganization(id: number): Promise<void> {
  return http.delete(`/api/auth/rbac/organizations/${id}`).then(() => undefined)
}

export function listWorkspaces(organizationId?: number): Promise<Workspace[]> {
  return http
    .get<Workspace[]>('/api/auth/rbac/workspaces', { params: organizationId === undefined ? {} : { organizationId } })
    .then((res) => res.data)
}

export function createWorkspace(payload: WorkspaceInput): Promise<Workspace> {
  return http.post<Workspace>('/api/auth/rbac/workspaces', payload).then((res) => res.data)
}

export function updateWorkspace(id: number, payload: WorkspaceInput): Promise<Workspace> {
  return http.put<Workspace>(`/api/auth/rbac/workspaces/${id}`, payload).then((res) => res.data)
}

export function deleteWorkspace(id: number): Promise<void> {
  return http.delete(`/api/auth/rbac/workspaces/${id}`).then(() => undefined)
}

// —— 资源授权绑定 ——

export function listGrants(query: GrantQuery = {}): Promise<Grant[]> {
  return http.get<Grant[]>('/api/auth/rbac/grants', { params: query }).then((res) => res.data)
}

export function createGrant(payload: GrantInput): Promise<Grant> {
  return http.post<Grant>('/api/auth/rbac/grants', payload).then((res) => res.data)
}

export function updateGrant(id: number, payload: GrantUpdateInput): Promise<Grant> {
  return http.put<Grant>(`/api/auth/rbac/grants/${id}`, payload).then((res) => res.data)
}

export function revokeGrant(id: number): Promise<void> {
  return http.delete(`/api/auth/rbac/grants/${id}`).then(() => undefined)
}