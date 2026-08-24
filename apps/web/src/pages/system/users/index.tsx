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
  createAuthUser,
  deleteAuthUser,
  listAuthUsers,
  updateAuthUser,
  type AuthUser,
  type CreateAuthUserInput,
  type UpdateAuthUserInput
} from '@/api/auth'
import { USERNAME_KEY } from '@/api/http'
import { formatDateTime } from '@/utils/format'

const statusMeta = {
  active: { color: 'success', text: '启用' },
  disabled: { color: 'default', text: '禁用' }
}

type UserFormInput = CreateAuthUserInput

export default function UserPage() {
  const { message } = App.useApp()
  const currentUsername = localStorage.getItem(USERNAME_KEY)
  const [allData, setAllData] = useState<AuthUser[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<AuthUser | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<UserFormInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listAuthUsers())
    } catch {
      message.error('登录账号列表加载失败')
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
      form.setFieldsValue({ ...editing, password: '' })
    } else {
      form.resetFields()
      form.setFieldsValue({ role: 'viewer', status: 'active' })
    }
  }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((user) => {
    if (keyword && !user.username.includes(keyword) && !(user.nickname ?? '').includes(keyword)) return false
    if (status && user.status !== status) return false
    return true
  }), [allData, keyword, status])

  const handleSubmit = async (values: UserFormInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        const payload: UpdateAuthUserInput = {
          password: values.password || undefined,
          nickname: values.nickname,
          email: values.email,
          phone: values.phone,
          role: values.role,
          status: values.status
        }
        await updateAuthUser(editing.id, payload)
        message.success('登录账号已更新')
      } else {
        await createAuthUser(values)
        message.success('登录账号已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查账号信息或管理员约束')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteAuthUser(id)
      message.success('登录账号已删除')
      await refresh()
    } catch {
      message.error('删除失败，不能删除当前账号或最后一个管理员')
    }
  }

  const columns: TableColumnsType<AuthUser> = [
    {
      title: '用户名',
      dataIndex: 'username',
      key: 'username',
      width: 150,
      render: (value: string) => (
        <Space>
          <span>{value}</span>
          {value === currentUsername && <Tag color="blue">当前账号</Tag>}
        </Space>
      )
    },
    { title: '昵称', dataIndex: 'nickname', key: 'nickname', width: 140, render: (value?: string) => value || '—' },
    { title: '邮箱', dataIndex: 'email', key: 'email', width: 200, render: (value?: string) => value || '—' },
    { title: '手机号', dataIndex: 'phone', key: 'phone', width: 140, render: (value?: string) => value || '—' },
    {
      title: '角色',
      dataIndex: 'role',
      key: 'role',
      width: 100,
      render: (value: string) => <Tag color={value === 'admin' ? 'purple' : 'default'}>{value === 'admin' ? '管理员' : '只读用户'}</Tag>
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: AuthUser['status']) => <Tag color={statusMeta[value].color}>{statusMeta[value].text}</Tag>
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      fixed: 'right',
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm
            title="确定删除该登录账号？"
            disabled={record.username === currentUsername}
            onConfirm={() => void handleDelete(record.id)}
          >
            <Button type="link" size="small" danger disabled={record.username === currentUsername}>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="登录账号与权限"
        description="认证账号由 Auth 服务统一管理；管理员可维护账号，只读用户不能修改平台资源。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增登录账号
          </Button>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>用户名/昵称</span>
            <Input placeholder="搜索用户名或昵称" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear style={{ width: 180 }} />
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
        <Table<AuthUser>
          size="small"
          bordered
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无登录账号' }}
        />
      </Card>

      <Modal
        title={editing ? '编辑登录账号' : '新增登录账号'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input disabled={Boolean(editing)} placeholder="例如：analyst" />
          </Form.Item>
          <Form.Item
            name="password"
            label={editing ? '新密码' : '初始密码'}
            extra={editing ? '留空表示不修改密码' : undefined}
            rules={[
              { required: !editing, message: '请输入初始密码' },
              { min: 12, message: '密码至少 12 位' }
            ]}
          >
            <Input.Password placeholder="至少 12 位" />
          </Form.Item>
          <Form.Item name="nickname" label="昵称"><Input placeholder="例如：数据分析师" /></Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ type: 'email', message: '邮箱格式不正确' }]}><Input placeholder="可选" /></Form.Item>
          <Form.Item name="phone" label="手机号"><Input placeholder="可选" /></Form.Item>
          <Form.Item name="role" label="角色" rules={[{ required: true }]}>
            <Select disabled={editing?.username === currentUsername} options={[
              { value: 'admin', label: '管理员（可修改平台资源）' },
              { value: 'viewer', label: '只读用户（仅查看与使用 AI）' }
            ]} />
          </Form.Item>
          <Form.Item name="status" label="状态" rules={[{ required: true }]}>
            <Select disabled={editing?.username === currentUsername} options={[
              { value: 'active', label: '启用' },
              { value: 'disabled', label: '禁用' }
            ]} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
