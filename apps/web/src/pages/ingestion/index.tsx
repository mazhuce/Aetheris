import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import { createTask, deleteTask, listTasks, runTask, updateTask, type IngestionTask, type IngestionTaskInput } from '@/api/ingestion'
import { formatDateTime } from '@/utils/format'

export default function IngestionPage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<IngestionTask[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<IngestionTask | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<IngestionTaskInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try { setAllData(await listTasks()) } catch { message.error('接入任务加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    if (open) { if (editing) form.setFieldsValue(editing); else form.resetFields() }
  }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((t) => !keyword || t.name.includes(keyword) || t.targetTable.includes(keyword)), [allData, keyword])

  const handleSubmit = async (values: IngestionTaskInput) => {
    setSubmitting(true)
    try {
      if (editing) { await updateTask(editing.id, values); message.success('任务已更新') }
      else { await createTask(values); message.success('任务已添加') }
      setOpen(false); setEditing(null); await refresh()
    } catch { message.error('保存失败') } finally { setSubmitting(false) }
  }

  const handleDelete = async (id: number) => {
    try { await deleteTask(id); message.success('任务已删除'); await refresh() } catch { message.error('删除失败') }
  }

  const handleRun = async (id: number) => {
    try { await runTask(id); message.success('任务运行成功'); await refresh() } catch { message.error('运行失败') }
  }

  const columns: TableColumnsType<IngestionTask> = [
    { title: '任务名称', dataIndex: 'name', key: 'name', width: 180 },
    { title: '来源类型', dataIndex: 'sourceType', key: 'sourceType', width: 110, render: (v: string) => <Tag>{v}</Tag> },
    { title: '目标表', dataIndex: 'targetTable', key: 'targetTable', width: 150 },
    { title: '调度', dataIndex: 'scheduleCron', key: 'scheduleCron', width: 120, render: (v?: string) => v || '手动' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => v === 'enabled' ? <Tag color="success">启用</Tag> : <Tag>停用</Tag> },
    { title: '上次结果', dataIndex: 'lastRunStatus', key: 'lastRunStatus', width: 100, render: (v?: string) => v ? <Tag color={v === 'success' ? 'success' : 'error'}>{v}</Tag> : '—' },
    { title: '上次运行', dataIndex: 'lastRunAt', key: 'lastRunAt', width: 170, render: (v?: string) => formatDateTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 180,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" icon={<PlayCircleOutlined />} onClick={() => void handleRun(record.id)}>运行</Button>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该任务？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader title="接入任务" description="配置数据接入任务，支持数据库、API、文件等多种来源。" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增任务</Button>} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item"><span>名称/目标表</span><Input placeholder="搜索名称或目标表" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>
      <Card size="small">
        <Table<IngestionTask> size="small" bordered rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1100 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无接入任务' }} />
      </Card>
      <Modal title={editing ? '编辑任务' : '新增任务'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width={560}>
        <Form form={form} layout="vertical" initialValues={{ sourceType: 'mysql', status: 'enabled' }} onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="sourceType" label="来源类型" rules={[{ required: true, message: '请选择类型' }]}><Select options={[{ value: 'mysql', label: 'MySQL' }, { value: 'postgresql', label: 'PostgreSQL' }, { value: 'api', label: 'API' }, { value: 'file', label: '文件' }]} /></Form.Item>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: true, message: '请输入目标表' }]}><Input placeholder="例如：ods_orders" /></Form.Item>
          <Form.Item name="scheduleCron" label="调度表达式"><Input placeholder="例如：0 0 2 * * ?" /></Form.Item>
          <Form.Item name="sourceConfig" label="来源配置"><Input.TextArea rows={3} placeholder="连接信息或 SQL" /></Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ value: 'enabled', label: '启用' }, { value: 'disabled', label: '停用' }]} /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}
