import type { Mock } from 'vitest'
import http from './http'
import {
  listRoles,
  createRole,
  updateRole,
  deleteRole,
  bindRolePermissions,
  listPermissions,
  listGroups,
  createGroup,
  listGroupMembers,
  addGroupMembers,
  removeGroupMember,
  listOrganizations,
  listWorkspaces,
  createWorkspace,
  listGrants,
  createGrant,
  updateGrant,
  revokeGrant
} from './rbac'
import type { AxiosResponse } from 'axios'

vi.mock('./http', () => ({
  default: {
    get: vi.fn(),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn()
  }
}))

const mockedHttp = http as unknown as Record<'get' | 'post' | 'put' | 'delete', Mock>

function resolved<T>(data: T) {
  return Promise.resolve({ data } as AxiosResponse<T>)
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('rbac api', () => {
  it('listRoles 请求角色列表', async () => {
    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await expect(listRoles()).resolves.toEqual([])
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/roles')
  })

  it('createRole 发送角色 payload', async () => {
    const payload = { code: 'steward', name: '数据管理员' }
    mockedHttp.post.mockReturnValueOnce(resolved({ id: 1, ...payload }))
    await createRole(payload)
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/rbac/roles', payload)
  })

  it('updateRole 使用 RESTful 路径', async () => {
    mockedHttp.put.mockReturnValueOnce(resolved({ id: 2 } as never))
    await updateRole(2, { code: 'steward', name: '数据管理员' })
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/rbac/roles/2', { code: 'steward', name: '数据管理员' })
  })

  it('bindRolePermissions 全量替换权限 codes', async () => {
    mockedHttp.put.mockReturnValueOnce(resolved(null))
    await bindRolePermissions(3, ['domain.view', 'metric.publish'])
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/rbac/roles/3/permissions', {
      codes: ['domain.view', 'metric.publish']
    })
  })

  it('deleteRole 与 listPermissions 路径正确', async () => {
    mockedHttp.delete.mockReturnValueOnce(resolved(null))
    await deleteRole(4)
    expect(mockedHttp.delete).toHaveBeenCalledWith('/api/auth/rbac/roles/4')

    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listPermissions()
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/permissions')
  })

  it('用户组 CRUD 与成员管理路径', async () => {
    mockedHttp.post.mockReturnValueOnce(resolved({ id: 1 } as never))
    await createGroup({ code: 'team_a', name: 'A 组' })
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/rbac/groups', { code: 'team_a', name: 'A 组' })

    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listGroups()
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/groups')

    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listGroupMembers(1)
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/groups/1/members')

    mockedHttp.post.mockReturnValueOnce(resolved(null))
    await addGroupMembers(1, [7, 8])
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/rbac/groups/1/members', { userIds: [7, 8] })

    mockedHttp.delete.mockReturnValueOnce(resolved(null))
    await removeGroupMember(1, 7)
    expect(mockedHttp.delete).toHaveBeenCalledWith('/api/auth/rbac/groups/1/members/7')
  })

  it('组织与项目空间端点路径', async () => {
    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listOrganizations()
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/organizations')

    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listWorkspaces()
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/workspaces', { params: {} })

    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listWorkspaces(9)
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/workspaces', { params: { organizationId: 9 } })

    mockedHttp.post.mockReturnValueOnce(resolved({ id: 1 } as never))
    await createWorkspace({ organizationId: 9, code: 'sc', name: '供应链', status: 'active' })
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/rbac/workspaces', {
      organizationId: 9,
      code: 'sc',
      name: '供应链',
      status: 'active'
    })
  })

  it('listGrants 传递过滤参数', async () => {
    mockedHttp.get.mockReturnValueOnce(resolved([]))
    await listGrants({ granteeType: 'user', resourceType: 'domain' })
    expect(mockedHttp.get).toHaveBeenCalledWith('/api/auth/rbac/grants', {
      params: { granteeType: 'user', resourceType: 'domain' }
    })
  })

  it('createGrant 发送主体、资源、动作与数据范围', async () => {
    const payload = {
      granteeType: 'group' as const,
      granteeId: 5,
      resourceType: 'domain',
      dataScope: { kind: 'domain', values: ['3', '4'] },
      actions: ['view/list', 'query']
    }
    mockedHttp.post.mockReturnValueOnce(resolved({ id: 1 } as never))
    await createGrant(payload)
    expect(mockedHttp.post).toHaveBeenCalledWith('/api/auth/rbac/grants', payload)
  })

  it('updateGrant 只调整范围、动作与启停', async () => {
    mockedHttp.put.mockReturnValueOnce(resolved({ id: 1 } as never))
    await updateGrant(1, { dataScope: null, actions: ['query'], enabled: false })
    expect(mockedHttp.put).toHaveBeenCalledWith('/api/auth/rbac/grants/1', {
      dataScope: null,
      actions: ['query'],
      enabled: false
    })
  })

  it('revokeGrant 撤销授权', async () => {
    mockedHttp.delete.mockReturnValueOnce(resolved(null))
    await expect(revokeGrant(1)).resolves.toBeUndefined()
    expect(mockedHttp.delete).toHaveBeenCalledWith('/api/auth/rbac/grants/1')
  })
})