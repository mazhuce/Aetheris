import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  Card,
  Form,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  createUser,
  deleteUser,
  listUsers,
  updateUser,
  type SysUser,
  type SysUserInput
} from '@/api/system'
import { formatDateTime } from '@/utils/format'

const statusMeta: Record<string, { color: string; text: string }> = {
  active: { color: 'success', text: '启用' },
  disabled: { color: 'default', text: '禁用' }
}

export default function UserPage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<SysUser[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<SysUser | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<SysUserInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listUsers())
    } catch {
      message.error('用户列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (open) {
      if (editing) form.setFieldsValue(editing)
      else form.resetFields()
    }
  }, [open, editing, form])

  const filtered = useMemo(() => {
    return allData.filter((user) => {
      if (keyword && !user.username.includes(keyword) && !(user.nickname ?? '').includes(keyword)) return false
      if (status && user.status !== status) return false
      return true
    })
  }, [allData, keyword, status])

  const handleSubmit = async (values: SysUserInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateUser(editing.id, values)
        message.success('用户已更新')
      } else {
        await createUser(values)
        message.success('用户已添加')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteUser(id)
      message.success('用户已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<SysUser> = [
    { title: '用户名', dataIndex: 'username', key: 'username', width: 140 },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 140, render: (value?: string) => value || '—' },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 200, render: (value?: string) => value || '—' },
    { title: '手机号', dataIndex: 'phone', key: 'phone', width: 140, render: (value?: string) => value || '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value?: string) => {
        const meta = statusMeta[value ?? ''] ?? { color: 'default', text: value ?? '—' }
        return <Tag color={meta.color}>{meta.text}</Tag>
      }
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该用户？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="用户管理"
        description="管理系统用户账号与基础信息。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增用户
          </Button>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>用户名/昵称</span>
            <Input placeholder="搜索用户名或昵称" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 180 }} />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select value={status} onChange={setStatus} allowClear placeholder="全部" style={{ width: 110 }}
              options={[{ value: 'active', label: '启用' }, { value: 'disabled', label: '禁用' }]} />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>

      <Card size="small">
        <Table<SysUser>
          size="small"
          bordered
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无用户' }}
        />
      </Card>

      <Modal
        title={editing ? '编辑用户' : '新增用户'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" initialValues={{ status: 'active' }} onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input placeholder="例如：analyst" />
          </Form.Item>
          <Form.Item name="nickname" label="昵称">
            <Input placeholder="例如：数据分析师" />
          </Form.Item>
          <Form.Item name="email" label="邮箱">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="phone" label="手机号">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 'active', label: '启用' }, { value: 'disabled', label: '禁用' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
