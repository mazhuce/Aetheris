import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Select, Table } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createWorkspace,
  deleteWorkspace,
  listOrganizations,
  listWorkspaces,
  updateWorkspace,
  type Organization,
  type Workspace,
  type WorkspaceInput
} from '@/api/rbac'
import { formatDateTime } from '@/utils/format'

type WorkspaceFormInput = WorkspaceInput

export default function WorkspacePage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<Workspace[]>([])
  const [organizations, setOrganizations] = useState<Organization[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [organizationFilter, setOrganizationFilter] = useState<number>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Workspace | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<WorkspaceFormInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [spaces, orgs] = await Promise.all([listWorkspaces(), listOrganizations()])
      setAllData(spaces)
      setOrganizations(orgs)
    } catch {
      message.error('项目空间列表加载失败')
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
      form.setFieldsValue({
        organizationId: editing.organizationId,
        code: editing.code,
        name: editing.name,
        description: editing.description,
        status: editing.status
      })
    } else {
      form.resetFields()
      form.setFieldsValue({ status: 'active' })
    }
  }, [open, editing, form])

  const organizationName = (id: number) => organizations.find((org) => org.id === id)?.name ?? `#${id}`

  const filtered = useMemo(() => allData.filter((space) => {
    if (organizationFilter && space.organizationId !== organizationFilter) return false
    if (keyword && !space.name.includes(keyword) && !space.code.includes(keyword)) return false
    return true
  }), [allData, organizationFilter, keyword])

  const handleSubmit = async (values: WorkspaceFormInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateWorkspace(editing.id, values)
        message.success('项目空间已更新')
      } else {
        await createWorkspace(values)
        message.success('项目空间已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查空间编码或所属组织')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteWorkspace(id)
      message.success('项目空间已删除')
      await refresh()
    } catch {
      message.error('删除失败，请确认该空间下没有授权绑定')
    }
  }

  const columns: TableColumnsType<Workspace> = [
    {
      title: '空间编码',
      dataIndex: 'code',
      key: 'code',
      width: 180,
      render: (value: string) => <code className="permission-option-code">{value}</code>
    },
    { title: '空间名称', dataIndex: 'name', key: 'name', width: 180 },
    {
      title: '所属组织',
      dataIndex: 'organizationId',
      key: 'organizationId',
      width: 160,
      render: (value: number) => organizationName(value)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: Workspace['status']) => (
        <StatusBadge tone={value === 'active' ? 'success' : 'neutral'}>
          {value === 'active' ? '启用' : '停用'}
        </StatusBadge>
      )
    },
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
          { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () => modal.confirm({
              title: `删除空间“${record.name}”？`,
              content: '删除前请先清理该空间下的授权绑定。',
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
        description="项目空间按组织隔离，是业务域、数据表与语义资产的授权容器；空间状态与资源级权限共同约束访问。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增空间
          </Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>所属组织</span>
            <Select
              className="filter-control-xs"
              placeholder="全部"
              allowClear
              value={organizationFilter}
              onChange={setOrganizationFilter}
              options={organizations.map((org) => ({ value: org.id, label: org.name }))}
            />
          </div>
          <div className="filter-item">
            <span>名称/编码</span>
            <Input placeholder="搜索空间名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="项目空间列表">
        <Table<Workspace>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1000 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无项目空间' }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑空间' : '新增空间'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="organizationId" label="所属组织" rules={[{ required: true, message: '请选择所属组织' }]}>
            <Select
              disabled={Boolean(editing)}
              placeholder="选择组织"
              options={organizations.map((org) => ({ value: org.id, label: org.name }))}
            />
          </Form.Item>
          <Form.Item
            name="code"
            label="空间编码"
            rules={[
              { required: true, message: '请输入空间编码' },
              { pattern: /^[a-z][a-z0-9_]*$/, message: '格式：小写字母开头，仅含小写字母、数字与下划线' }
            ]}
          >
            <Input disabled={Boolean(editing)} placeholder="例如：supply_chain" />
          </Form.Item>
          <Form.Item name="name" label="空间名称" rules={[{ required: true, message: '请输入空间名称' }]}>
            <Input placeholder="例如：供应链项目" />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select options={[
              { value: 'active', label: '启用' },
              { value: 'disabled', label: '停用' }
            ]} />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}