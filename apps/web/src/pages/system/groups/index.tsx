import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Select, Space, Table } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import RowActions from '@/components/RowActions'
import {
  addGroupMembers,
  createGroup,
  deleteGroup,
  listGroupMembers,
  listGroups,
  removeGroupMember,
  updateGroup,
  type GroupMember,
  type UserGroup,
  type UserGroupInput
} from '@/api/rbac'
import { listAuthUsers } from '@/api/auth'
import { formatDateTime } from '@/utils/format'

type GroupFormInput = UserGroupInput

export default function GroupPage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<UserGroup[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<UserGroup | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<GroupFormInput>()

  // 成员管理
  const [memberOpen, setMemberOpen] = useState(false)
  const [memberTarget, setMemberTarget] = useState<UserGroup | null>(null)
  const [members, setMembers] = useState<GroupMember[]>([])
  const [memberLoading, setMemberLoading] = useState(false)
  const [candidateUsers, setCandidateUsers] = useState<{ value: number; label: string }[]>([])
  const [selectedUserIds, setSelectedUserIds] = useState<number[]>([])
  const [memberSubmitting, setMemberSubmitting] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listGroups())
    } catch {
      message.error('用户组列表加载失败')
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

  const filtered = useMemo(() => allData.filter((group) => {
    if (keyword && !group.name.includes(keyword) && !group.code.includes(keyword)) return false
    return true
  }), [allData, keyword])

  const handleSubmit = async (values: GroupFormInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateGroup(editing.id, values)
        message.success('用户组已更新')
      } else {
        await createGroup(values)
        message.success('用户组已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请检查用户组编码')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteGroup(id)
      message.success('用户组已删除')
      await refresh()
    } catch {
      message.error('删除失败，请确认该用户组未被授权引用')
    }
  }

  const openMembers = async (group: UserGroup) => {
    setMemberTarget(group)
    setMemberOpen(true)
    setSelectedUserIds([])
    setMemberLoading(true)
    try {
      const [groupMembers, users] = await Promise.all([listGroupMembers(group.id), listAuthUsers()])
      setMembers(groupMembers)
      const existingIds = new Set(groupMembers.map((member) => member.userId))
      setCandidateUsers(
        users
          .filter((user) => !existingIds.has(user.id))
          .map((user) => ({ value: user.id, label: user.nickname ? `${user.username}（${user.nickname}）` : user.username }))
      )
    } catch {
      message.error('用户组成员加载失败')
    } finally {
      setMemberLoading(false)
    }
  }

  const saveMembers = async () => {
    if (!memberTarget) return
    setMemberSubmitting(true)
    try {
      if (selectedUserIds.length > 0) {
        await addGroupMembers(memberTarget.id, selectedUserIds)
        message.success(`已向“${memberTarget.name}”添加 ${selectedUserIds.length} 名成员`)
      }
      await openMembers(memberTarget)
      setSelectedUserIds([])
    } catch {
      message.error('成员保存失败，请重试')
    } finally {
      setMemberSubmitting(false)
    }
  }

  const removeMember = async (userId: number) => {
    if (!memberTarget) return
    try {
      await removeGroupMember(memberTarget.id, userId)
      message.success('成员已移除')
      await openMembers(memberTarget)
    } catch {
      message.error('成员移除失败')
    }
  }

  const columns: TableColumnsType<UserGroup> = [
    {
      title: '组编码',
      dataIndex: 'code',
      key: 'code',
      width: 180,
      render: (value: string) => <code className="permission-option-code">{value}</code>
    },
    { title: '组名称', dataIndex: 'name', key: 'name', width: 180 },
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
          { key: 'members', label: '管理成员', onClick: () => void openMembers(record) },
          { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () => modal.confirm({
              title: `删除用户组“${record.name}”？`,
              content: '删除后该组从所有授权中失效，此操作不可恢复。',
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
        description="维护用户组及其成员；用户组可整体授予资源权限并约束数据范围，是批量授权与权限回收的基础。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增用户组
          </Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码</span>
            <Input placeholder="搜索用户组名称或编码" value={keyword} onChange={(event) => setKeyword(event.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="用户组列表">
        <Table<UserGroup>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无用户组' }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑用户组' : '新增用户组'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item
            name="code"
            label="组编码"
            rules={[
              { required: true, message: '请输入组编码' },
              { pattern: /^[a-z][a-z0-9_]*$/, message: '格式：小写字母开头，仅含小写字母、数字与下划线' }
            ]}
          >
            <Input disabled={Boolean(editing)} placeholder="例如：analytics_team" />
          </Form.Item>
          <Form.Item name="name" label="组名称" rules={[{ required: true, message: '请输入组名称' }]}>
            <Input placeholder="例如：分析团队" />
          </Form.Item>
          <Form.Item name="description" label="描述">
            <Input.TextArea rows={2} placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`管理成员：${memberTarget?.name ?? ''}`}
        open={memberOpen}
        onCancel={() => setMemberOpen(false)}
        footer={null}
        width={640}
      >
        {memberLoading ? (
          <div className="route-loading">成员列表加载中…</div>
        ) : (
          <>
            <div style={{ marginBottom: 16 }}>
              <Space.Compact style={{ width: '100%' }}>
                <Select
                  mode="multiple"
                  placeholder="选择要添加的用户（按用户名搜索）"
                  style={{ width: '100%' }}
                  showSearch
                  optionFilterProp="label"
                  value={selectedUserIds}
                  onChange={(values) => setSelectedUserIds(values as number[])}
                  options={candidateUsers}
                  notFoundContent="无可添加的用户"
                />
                <Button type="primary" loading={memberSubmitting} disabled={selectedUserIds.length === 0} onClick={() => void saveMembers()}>
                  添加
                </Button>
              </Space.Compact>
            </div>
            <Table<GroupMember>
              size="small"
              rowKey="userId"
              dataSource={members}
              pagination={false}
              locale={{ emptyText: '该用户组暂无成员' }}
              columns={[
                { title: '用户 ID', dataIndex: 'userId', key: 'userId', width: 100 },
                { title: '用户名', dataIndex: 'username', key: 'username', width: 160 },
                { title: '昵称', dataIndex: 'nickname', key: 'nickname', render: (value?: string) => value || '—' },
                {
                  title: '操作',
                  key: 'action',
                  width: 80,
                  align: 'right',
                  render: (_, member) => (
                    <Button size="small" danger type="text" onClick={() => void removeMember(member.userId)}>移除</Button>
                  )
                }
              ]}
            />
          </>
        )}
      </Modal>
    </>
  )
}