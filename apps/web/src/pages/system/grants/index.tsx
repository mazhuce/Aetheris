import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, AutoComplete, Button, Form, Input, InputNumber, Modal, Select, Space, Switch, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createGrant,
  listGrants,
  listGroups,
  listRoles,
  revokeGrant,
  updateGrant,
  type DataScope,
  type Grant,
  type GranteeType
} from '@/api/rbac'
import { listAuthUsers } from '@/api/auth'
import { formatDateTime } from '@/utils/format'

/** 平台已支持授权判定的资源类型（与服务端决策引擎对齐；允许自定义输入）。 */
const RESOURCE_TYPES = [
  'organization', 'workspace', 'domain', 'table', 'field', 'metric',
  'model', 'dimension', 'datasource', 'dataset', 'dashboard', 'data_service', 'service'
]

const GRANTEE_LABELS: Record<GranteeType, string> = {
  user: '用户',
  group: '用户组',
  role: '角色'
}

const SCOPE_KINDS = [
  { value: 'workspace', label: '项目空间级（values 填空间 id）' },
  { value: 'domain', label: '业务域级（values 填业务域 id）' },
  { value: 'table', label: '表级（values 填表 id）' },
  { value: 'field', label: '字段级（values 填字段 id）' },
  { value: 'metric', label: '指标级（values 填指标 id）' }
]

interface GranteeOption {
  value: number
  label: string
}

interface GrantFormInput {
  granteeType: GranteeType
  granteeId: number
  resourceType: string
  resourceId?: number
  scopeKind?: string
  scopeValues?: string
  actions: string
  enabled: boolean
}

export default function GrantPage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<Grant[]>([])
  const [loading, setLoading] = useState(false)
  const [filterGranteeType, setFilterGranteeType] = useState<string>()
  const [filterResourceType, setFilterResourceType] = useState<string>()

  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Grant | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<GrantFormInput>()

  // 被授权主体选项
  const [userOptions, setUserOptions] = useState<GranteeOption[]>([])
  const [groupOptions, setGroupOptions] = useState<GranteeOption[]>([])
  const [roleOptions, setRoleOptions] = useState<GranteeOption[]>([])
  const granteeType = Form.useWatch('granteeType', form)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listGrants({
        granteeType: filterGranteeType,
        resourceType: filterResourceType
      }))
    } catch {
      message.error('授权列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, filterGranteeType, filterResourceType])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 打开表单前准备主体候选项。 */
  const prepareOptions = async () => {
    try {
      const [users, groups, roles] = await Promise.all([listAuthUsers(), listGroups(), listRoles()])
      setUserOptions(users.map((user) => ({ value: user.id, label: user.nickname ? `${user.username}（${user.nickname}）` : user.username })))
      setGroupOptions(groups.map((group) => ({ value: group.id, label: `${group.name}（${group.code}）` })))
      setRoleOptions(roles.map((role) => ({ value: role.id, label: `${role.name}（${role.code}）` })))
    } catch {
      message.error('授权主体列表加载失败')
    }
  }

  const openCreate = async () => {
    setEditing(null)
    await prepareOptions()
    form.resetFields()
    form.setFieldsValue({ granteeType: 'user', enabled: true })
    setOpen(true)
  }

  const openEdit = async (grant: Grant) => {
    setEditing(grant)
    await prepareOptions()
    form.setFieldsValue({
      granteeType: grant.granteeType,
      granteeId: grant.granteeId,
      resourceType: grant.resourceType,
      resourceId: grant.resourceId ?? undefined,
      scopeKind: grant.dataScope?.kind,
      scopeValues: grant.dataScope?.values.join(','),
      actions: grant.actions.join(','),
      enabled: grant.enabled !== false
    })
    setOpen(true)
  }

  const granteeOptions = useMemo(() => {
    if (granteeType === 'group') return groupOptions
    if (granteeType === 'role') return roleOptions
    return userOptions
  }, [granteeType, userOptions, groupOptions, roleOptions])

  const handleSubmit = async (values: GrantFormInput) => {
    setSubmitting(true)
    try {
      const actions = values.actions.split(',').map((action) => action.trim()).filter(Boolean)
      const dataScope: DataScope | null = values.scopeKind && values.scopeValues
        ? {
            kind: values.scopeKind,
            values: values.scopeValues.split(',').map((value) => value.trim()).filter(Boolean)
          }
        : null
      if (editing) {
        await updateGrant(editing.id, { dataScope, actions, enabled: values.enabled })
        message.success('授权已更新')
      } else {
        await createGrant({
          granteeType: values.granteeType,
          granteeId: values.granteeId,
          resourceType: values.resourceType,
          resourceId: values.resourceId,
          dataScope,
          actions,
          enabled: values.enabled
        })
        message.success('授权已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查主体、资源与动作是否合法')
    } finally {
      setSubmitting(false)
    }
  }

  const handleRevoke = async (id: number) => {
    try {
      await revokeGrant(id)
      message.success('授权已撤销')
      await refresh()
    } catch {
      message.error('撤销失败，请重试')
    }
  }

  const filtered = useMemo(() => allData, [allData])

  const columns: TableColumnsType<Grant> = [
    {
      title: '主体',
      key: 'grantee',
      width: 190,
      render: (_, record) => (
        <Space>
          <Tag>{GRANTEE_LABELS[record.granteeType]}</Tag>
          <span>{record.granteeName ?? `#${record.granteeId}`}</span>
        </Space>
      )
    },
    {
      title: '资源',
      key: 'resource',
      width: 200,
      render: (_, record) => (
        <Space direction="vertical" size={0}>
          <span><Tag className="permission-option-code">{record.resourceType}</Tag></span>
          {record.resourceId != null && <span className="permission-option-code">id={record.resourceId}</span>}
        </Space>
      )
    },
    {
      title: '数据范围',
      key: 'scope',
      width: 220,
      render: (_, record) => {
        if (!record.dataScope || record.dataScope.values.length === 0) return <span>不限定</span>
        return (
          <Space direction="vertical" size={0}>
            <Tag color="blue">{record.dataScope.kind}</Tag>
            <span className="permission-option-code">{record.dataScope.values.join(', ')}</span>
          </Space>
        )
      }
    },
    {
      title: '动作',
      dataIndex: 'actions',
      key: 'actions',
      render: (actions: string[]) => (
        <Space size={4} wrap>
          {actions.map((action) => <Tag key={action} className="permission-option-code">{action}</Tag>)}
        </Space>
      )
    },
    {
      title: '状态',
      dataIndex: 'enabled',
      key: 'enabled',
      width: 90,
      render: (enabled?: boolean) => (
        <StatusBadge tone={enabled === false ? 'neutral' : 'success'}>{enabled === false ? '停用' : '生效'}</StatusBadge>
      )
    },
    { title: '授权人', dataIndex: 'grantedBy', key: 'grantedBy', width: 110, render: (value?: string) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      fixed: 'right',
      render: (_, record) => (
        <RowActions items={[
          { key: 'edit', label: '编辑', onClick: () => void openEdit(record) },
          {
            key: 'revoke',
            label: '撤销授权',
            danger: true,
            onClick: () => modal.confirm({
              title: '撤销该授权？',
              content: '撤销后权限版本立即递增，旧授权不会继续放行；此操作进入审计日志。',
              okText: '确认撤销',
              okButtonProps: { danger: true },
              onOk: () => handleRevoke(record.id)
            })
          }
        ]} />
      )
    }
  ]

  return (
    <>
      <PageHeader
        description="资源授权把“谁能对什么资源做什么”落实到人、组与角色，并可叠加数据范围；每次授权、修改与撤销都会递增权限版本并写入审计。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => void openCreate()}>
            新增授权
          </Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>主体类型</span>
            <Select
              className="filter-control-xs"
              placeholder="全部"
              allowClear
              value={filterGranteeType}
              onChange={setFilterGranteeType}
              options={[
                { value: 'user', label: '用户' },
                { value: 'group', label: '用户组' },
                { value: 'role', label: '角色' }
              ]}
            />
          </div>
          <div className="filter-item">
            <span>资源类型</span>
            <Select
              className="filter-control-xs"
              placeholder="全部"
              allowClear
              value={filterResourceType}
              onChange={setFilterResourceType}
              options={RESOURCE_TYPES.map((type) => ({ value: type, label: type }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="授权关系列表">
        <Table<Grant>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无授权关系' }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑授权' : '新增授权'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={560}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="granteeType" label="主体类型" rules={[{ required: true }]}>
            <Select
              disabled={Boolean(editing)}
              onChange={() => form.setFieldValue('granteeId', undefined)}
              options={[
                { value: 'user', label: '用户' },
                { value: 'group', label: '用户组' },
                { value: 'role', label: '角色' }
              ]}
            />
          </Form.Item>
          <Form.Item name="granteeId" label="主体" rules={[{ required: true, message: '请选择被授权主体' }]}>
            <Select
              disabled={Boolean(editing)}
              showSearch
              optionFilterProp="label"
              placeholder="选择用户、用户组或角色"
              options={granteeOptions}
              notFoundContent="请先在前置页面创建对应主体"
            />
          </Form.Item>
          <Form.Item name="resourceType" label="资源类型" rules={[{ required: true, message: '请输入资源类型' }]}>
            <AutoComplete options={RESOURCE_TYPES.map((type) => ({ value: type }))} placeholder="例如：domain / table / metric" />
          </Form.Item>
          <Form.Item name="resourceId" label="资源 ID（可选）" extra="不填表示该类型下所有资源">
            <InputNumber style={{ width: '100%' }} min={1} placeholder="例如：12" />
          </Form.Item>
          <Form.Item label="数据范围（可选）" extra="叠加在资源权限之上，按层级收窄数据可见性">
            <div className="data-scope-block">
              <Form.Item name="scopeKind" noStyle>
                <Select
                  allowClear
                  placeholder="选择范围层级（不选 = 不限定）"
                  options={SCOPE_KINDS}
                />
              </Form.Item>
              <Form.Item name="scopeValues" noStyle>
                <Input placeholder="范围值，多个用英文逗号分隔（如 5,6）" style={{ marginTop: 8 }} />
              </Form.Item>
            </div>
          </Form.Item>
          <Form.Item
            name="actions"
            label="权限动作"
            extra={<span className="grant-actions-hint">多个动作用英文逗号分隔，如 view/list,query；支持：view/list、query、create、update、delete、submit_review、approve、publish、deprecate、grant、execute、export</span>}
            rules={[{ required: true, message: '请输入权限动作' }]}
          >
            <Input placeholder="例如：view/list,query" />
          </Form.Item>
          <Form.Item name="enabled" label="是否生效" valuePropName="checked">
            <Switch />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}