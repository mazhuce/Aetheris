import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import { createPolicy, deletePolicy, listPolicies, updatePolicy, type GovernancePolicy, type GovernancePolicyInput } from '@/api/governance'
import { formatDateTime } from '@/utils/format'

const typeMeta: Record<string, string> = { masking: '脱敏', classification: '分类分级', lifecycle: '生命周期', access: '访问控制' }

export default function GovernancePage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<GovernancePolicy[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<GovernancePolicy | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<GovernancePolicyInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try { setAllData(await listPolicies()) } catch { message.error('治理策略加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => { if (open) { if (editing) form.setFieldsValue(editing); else form.resetFields() } }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((p) => !keyword || p.name.includes(keyword) || p.targetTable.includes(keyword)), [allData, keyword])

  const handleSubmit = async (values: GovernancePolicyInput) => {
    setSubmitting(true)
    try {
      if (editing) { await updatePolicy(editing.id, values); message.success('策略已更新') }
      else { await createPolicy(values); message.success('策略已添加') }
      setOpen(false); setEditing(null); await refresh()
    } catch { message.error('保存失败') } finally { setSubmitting(false) }
  }

  const handleDelete = async (id: number) => {
    try { await deletePolicy(id); message.success('策略已删除'); await refresh() } catch { message.error('删除失败') }
  }

  const columns: TableColumnsType<GovernancePolicy> = [
    { title: '策略名称', dataIndex: 'name', key: 'name', width: 180 },
    { title: '策略类型', dataIndex: 'policyType', key: 'policyType', width: 110, render: (v: string) => <Tag>{typeMeta[v] ?? v}</Tag> },
    { title: '目标表', dataIndex: 'targetTable', key: 'targetTable', width: 150 },
    { title: '规则', dataIndex: 'ruleExpression', key: 'ruleExpression', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => v === 'enabled' ? <Tag color="success">启用</Tag> : <Tag>停用</Tag> },
    { title: '说明', dataIndex: 'description', key: 'description', width: 200, render: (v?: string) => v || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v?: string) => formatDateTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该策略？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader title="治理策略" description="定义数据分类分级、脱敏、生命周期与访问控制策略。" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增策略</Button>} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item"><span>名称/目标表</span><Input placeholder="搜索名称或目标表" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>
      <Card size="small">
        <Table<GovernancePolicy> size="small" bordered rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1100 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无治理策略' }} />
      </Card>
      <Modal title={editing ? '编辑策略' : '新增策略'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width={560}>
        <Form form={form} layout="vertical" initialValues={{ policyType: 'masking', status: 'enabled' }} onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="name" label="策略名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="policyType" label="策略类型" rules={[{ required: true, message: '请选择类型' }]}><Select options={[{ value: 'masking', label: '脱敏' }, { value: 'classification', label: '分类分级' }, { value: 'lifecycle', label: '生命周期' }, { value: 'access', label: '访问控制' }]} /></Form.Item>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: true, message: '请输入目标表' }]}><Input /></Form.Item>
          <Form.Item name="ruleExpression" label="规则表达式"><Input.TextArea rows={3} placeholder="例如：phone 或 L3" /></Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ value: 'enabled', label: '启用' }, { value: 'disabled', label: '停用' }]} /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}
