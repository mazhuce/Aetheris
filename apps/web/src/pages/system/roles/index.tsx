import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Checkbox, Form, Input, Modal, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import RowActions from '@/components/RowActions'
import {
  bindRolePermissions,
  createRole,
  deleteRole,
  listPermissions,
  listRolePermissionCodes,
  listRoles,
  updateRole,
  type RbacPermission,
  type RbacRole,
  type RbacRoleInput
} from '@/api/rbac'
import { formatDateTime } from '@/utils/format'

type RoleFormInput = RbacRoleInput

export default function RolePage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<RbacRole[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<RbacRole | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<RoleFormInput>()

  // 权限集合绑定
  const [permissionOpen, setPermissionOpen] = useState(false)
  const [permissionTarget, setPermissionTarget] = useState<RbacRole | null>(null)
  const [permissions, setPermissions] = useState<RbacPermission[]>([])
  const [checkedCodes, setCheckedCodes] = useState<string[]>([])
  const [permissionLoading, setPermissionLoading] = useState(false)
  const [permissionSubmitting, setPermissionSubmitting] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listRoles())
    } catch {
      message.error('角色列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (!open) return
    if (editing) {
      form.setFieldsValue({ code: editing.code, name: editing.name, description: editing.description })
    } else {
      form.resetFields()
    }
  }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((role) => {
    if (keyword && !role.name.includes(keyword) && !role.code.includes(keyword)) return false
    return true
  }), [allData, keyword])

  const handleSubmit = async (values: RoleFormInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateRole(editing.id, values)
        message.success('角色已更新')
      } else {
        await createRole(values)
        message.success('角色已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查角色编码或系统角色约束')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteRole(id)
      message.success('角色已删除')
      await refresh()
    } catch {
      message.error('删除失败，系统角色（sys_admin/sys_viewer）不允许删除')
    }
  }

  const openPermissionBinding = async (role: RbacRole) => {
    setPermissionTarget(role)
    setPermissionOpen(true)
    setPermissionLoading(true)
    try {
      const [catalog, bound] = await Promise.all([listPermissions(), listRolePermissionCodes(role.id)])
      setPermissions(catalog)
      setCheckedCodes(bound)
    } catch {
      message.error('权限集合加载失败')
    } finally {
      setPermissionLoading(false)
    }
  }

  const savePermissionBinding = async () => {
    if (!permissionTarget) return
    setPermissionSubmitting(true)
    try {
      await bindRolePermissions(permissionTarget.id, checkedCodes)
      message.success(`“${permissionTarget.name}”的权限集合已更新`)
      setPermissionOpen(false)
    } catch {
      message.error('权限集合保存失败，请重试')
    } finally {
      setPermissionSubmitting(false)
    }
  }

  const permissionGroups = useMemo(() => {
    const groups = new Map<string, RbacPermission[]>()
    for (const permission of permissions) {
      const list = groups.get(permission.resourceType) ?? []
      list.push(permission)
      groups.set(permission.resourceType, list)
    }
    return [...groups.entries()].sort(([a], [b]) => a.localeCompare(b))
  }, [permissions])

  const columns: TableColumnsType<RbacRole> = [
    {
      title: '角色编码',
      dataIndex: 'code',
      key: 'code',
      width: 180,
      render: (value: string, record) => (
        <Space>
          <span>{value}</span>
          {record.isSystem && <Tag color="blue">系统角色</Tag>}
        </Space>
      )
    },
    { title: '角色名称', dataIndex: 'name', key: 'name', width: 160 },
    { title: '描述', dataIndex: 'description', key: 'description', render: (value?: string) => value || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      fixed: 'right',
      render: (_, record) => (
        <RowActions items={[
          {
            key: 'permissions',
            label: '配置权限集合',
            onClick: () => void openPermissionBinding(record)
          },
          {
            key: 'edit',
            label: '编辑',
            disabled: Boolean(record.isSystem),
            onClick: () => { setEditing(record); setOpen(true) }
          },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            disabled: Boolean(record.isSystem),
            onClick: () => modal.confirm({
              title: `删除角色“${record.name}”？`,
              content: '删除后该角色从所有授权与绑定中失效，此操作不可恢复。',
              okText: '确认删除',
              okButtonProps: { danger: true },
              onOk: () => handleDelete(record.id)
            })
          }
        ]} />
      )
    }
  ]

  return (
    <>
      <PageHeader
        description="维护角色及其权限集合；sys_admin / sys_viewer 为系统角色，与既有 admin/viewer 登录账号保持兼容，不允许修改或删除。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增角色
          </Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码</span>
            <Input placeholder="搜索角色名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="角色列表">
        <Table<RbacRole>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无角色' }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑角色' : '新增角色'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item
            name="code"
            label="角色编码"
            extra="小写字母开头，仅含小写字母、数字与下划线"
            rules={[
              { required: true, message: '请输入角色编码' },
              { pattern: /^[a-z][a-z0-9_]*$/, message: '格式：小写字母开头，仅含小写字母、数字与下划线' }
            ]}
          >
            <Input disabled={Boolean(editing)} placeholder="例如：domain_data_steward" />
          </Form.Item>
          <Form.Item name="name" label="角色名称" rules={[{ required: true, message: '请输入角色名称' }]}>
            <Input placeholder="例如：数据域管理员" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`配置权限集合：${permissionTarget?.name ?? ''}`}
        open={permissionOpen}
        onCancel={() => setPermissionOpen(false)}
        onOk={() => void savePermissionBinding()}
        confirmLoading={permissionSubmitting}
        width={720}
        destroyOnClose
      >
        <p className="permission-note">勾选该角色可执行的权限动作；变更将递增权限版本并立即对后续判定生效。</p>
        <div style={{ maxHeight: 420, overflowY: 'auto' }}>
          {permissionLoading ? (
            <div className="route-loading">权限目录加载中…</div>
          ) : permissionGroups.length === 0 ? (
            <div className="route-loading">暂无权限目录</div>
          ) : (
            <Checkbox.Group
              style={{ display: 'flex', flexDirection: 'column', gap: 12 }}
              value={checkedCodes}
              onChange={(values) => setCheckedCodes(values.map(String))}
            >
              {permissionGroups.map(([resourceType, list]) => (
                <div key={resourceType} className="permission-group">
                  <div className="permission-group-header">{resourceType}</div>
                  <div className="permission-group-options">
                    {list.map((permission) => (
                      <Checkbox key={permission.code} value={permission.code}>
                        <span className="permission-option-name">{permission.name}</span>
                        <span className="permission-option-code">{permission.code}</span>
                      </Checkbox>
                    ))}
                  </div>
                </div>
              ))}
            </Checkbox.Group>
          )}
        </div>
      </Modal>
    </>
  )
}