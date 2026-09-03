import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Table } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import RowActions from '@/components/RowActions'
import {
  createOrganization,
  deleteOrganization,
  listOrganizations,
  updateOrganization,
  type Organization,
  type OrganizationInput
} from '@/api/rbac'
import { formatDateTime } from '@/utils/format'

type OrgFormInput = OrganizationInput

export default function OrganizationPage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<Organization[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<Organization | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<OrgFormInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listOrganizations())
    } catch {
      message.error('组织列表加载失败')
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

  const filtered = useMemo(() => allData.filter((org) => {
    if (keyword && !org.name.includes(keyword) && !org.code.includes(keyword)) return false
    return true
  }), [allData, keyword])

  const handleSubmit = async (values: OrgFormInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateOrganization(editing.id, values)
        message.success('组织已更新')
      } else {
        await createOrganization(values)
        message.success('组织已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查组织编码')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteOrganization(id)
      message.success('组织已删除')
      await refresh()
    } catch {
      message.error('删除失败，请确认该组织下没有项目空间或授权引用')
    }
  }

  const columns: TableColumnsType<Organization> = [
    {
      title: '组织编码',
      dataIndex: 'code',
      key: 'code',
      width: 180,
      render: (value: string) => <code className="permission-option-code">{value}</code>
    },
    { title: '组织名称', dataIndex: 'name', key: 'name', width: 200 },
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
              title: `删除组织“${record.name}”？`,
              content: '删除前请先清理其下的项目空间与授权绑定。',
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
        description="组织是数据范围的最上层容器；项目空间归属于组织，资源授权可按组织、空间逐级收窄。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增组织
          </Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码</span>
            <Input placeholder="搜索组织名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="组织列表">
        <Table<Organization>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无组织' }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑组织' : '新增组织'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item
            name="code"
            label="组织编码"
            rules={[
              { required: true, message: '请输入组织编码' },
              { pattern: /^[a-z][a-z0-9_]*$/, message: '格式：小写字母开头，仅含小写字母、数字与下划线' }
            ]}
          >
            <Input disabled={Boolean(editing)} placeholder="例如：retail" />
          </Form.Item>
          <Form.Item name="name" label="组织名称" rules={[{ required: true, message: '请输入组织名称' }]}>
            <Input placeholder="例如：零售事业部" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}