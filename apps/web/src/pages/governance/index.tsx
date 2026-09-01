import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Select, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import { createPolicy, deletePolicy, listPolicies, updatePolicy, type GovernancePolicy, type GovernancePolicyInput } from '@/api/governance'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const typeMeta: Record<string, string> = { masking: '脱敏', classification: '分类分级', lifecycle: '生命周期', access: '访问控制' }

export default function GovernancePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
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
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => <StatusBadge tone={v === 'enabled' ? 'success' : 'neutral'}>{v === 'enabled' ? '启用' : '停用'}</StatusBadge> },
    { title: '说明', dataIndex: 'description', key: 'description', width: 200, render: (v?: string) => v || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v?: string) => formatDateTime(v) }
  ]

  if (canManage) columns.push({
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      render: (_, record) => (
        <RowActions items={[
          { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () => modal.confirm({
              title: `删除策略“${record.name}”？`,
              content: '删除后该治理策略将停止生效，此操作不可恢复。',
              okText: '确认删除',
              okButtonProps: { danger: true },
              onOk: () => handleDelete(record.id)
            })
          }
        ]} />
      )
    })

  return (
    <>
      <PageHeader description="统一管理数据分类分级与安全治理策略，覆盖脱敏、生命周期与访问控制。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增策略</Button> : <Tag>只读访问</Tag>} />
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item"><span>名称/目标表</span><Input placeholder="搜索名称或目标表" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="治理策略列表">
        <Table<GovernancePolicy> size="small" rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1000 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无治理策略' }} />
      </DataSurface>
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
