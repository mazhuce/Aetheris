import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Input, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import { listPermissions, type RbacPermission } from '@/api/rbac'

/** 权限动作中文说明（与后端决策引擎支持的动作对齐）。 */
const actionLabels: Record<string, string> = {
  'view/list': '查看/列表',
  view: '查看',
  list: '列表',
  query: '查询',
  create: '新建',
  update: '修改',
  delete: '删除',
  submit_review: '提交评审',
  approve: '审批',
  publish: '发布',
  deprecate: '下线/废弃',
  grant: '授权',
  execute: '执行',
  export: '导出',
  manage: '系统管理'
}

export default function PermissionPage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<RbacPermission[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [resourceType, setResourceType] = useState<string>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listPermissions())
    } catch {
      message.error('权限目录加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const resourceTypes = useMemo(
    () => [...new Set(allData.map((permission) => permission.resourceType))].sort(),
    [allData]
  )

  const filtered = useMemo(() => allData.filter((permission) => {
    if (resourceType && permission.resourceType !== resourceType) return false
    if (keyword && !permission.name.includes(keyword) && !permission.code.includes(keyword)) return false
    return true
  }), [allData, resourceType, keyword])

  const columns: TableColumnsType<RbacPermission> = [
    {
      title: '权限编码',
      dataIndex: 'code',
      key: 'code',
      width: 260,
      render: (value: string) => <code className="permission-option-code">{value}</code>
    },
    { title: '权限名称', dataIndex: 'name', key: 'name', width: 220 },
    {
      title: '资源类型',
      dataIndex: 'resourceType',
      key: 'resourceType',
      width: 160,
      render: (value: string) => <Tag>{value}</Tag>
    },
    {
      title: '动作',
      dataIndex: 'action',
      key: 'action',
      width: 140,
      render: (value: string) => actionLabels[value] ?? value
    },
    { title: '说明', dataIndex: 'description', key: 'description', render: (value?: string) => value || '—' }
  ]

  return (
    <>
      <PageHeader description="查看平台权限目录：每个权限由资源类型与动作组成，可被绑定到角色，并经由资源授权与数据范围进一步收窄。" />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>资源类型</span>
            <Input
              className="filter-control-xs"
              placeholder="全部"
              value={resourceType}
              onChange={(event) => setResourceType(event.target.value || undefined)}
              allowClear
            />
          </div>
          <div className="filter-item">
            <span>名称/编码</span>
            <Input placeholder="搜索权限名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface
        label="权限目录"
        description={`共 ${resourceTypes.length} 类资源、${allData.length} 个权限动作；权限绑定到角色由“角色管理”完成。`}
      >
        <Table<RbacPermission>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 15, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无权限目录' }}
        />
      </DataSurface>
    </>
  )
}