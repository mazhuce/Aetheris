import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Select, Table, Tag, Tooltip } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import { createTask, deleteTask, listTasks, runTask, updateTask, type IngestionTask, type IngestionTaskInput } from '@/api/ingestion'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

export default function IngestionPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
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
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => <StatusBadge tone={v === 'enabled' ? 'success' : 'neutral'}>{v === 'enabled' ? '启用' : '停用'}</StatusBadge> },
    { title: '上次结果', dataIndex: 'lastRunStatus', key: 'lastRunStatus', width: 100, render: (v?: string) => v ? <StatusBadge tone={v === 'success' ? 'success' : 'danger'}>{v === 'success' ? '成功' : '失败'}</StatusBadge> : '—' },
    { title: '上次运行', dataIndex: 'lastRunAt', key: 'lastRunAt', width: 170, render: (v?: string) => formatDateTime(v) }
  ]

  if (canManage) columns.push({
      title: '操作',
      key: 'action',
      width: 96,
      align: 'right',
      render: (_, record) => (
        <RowActions
          primary={(
            <Tooltip title="立即运行">
              <Button type="text" size="small" icon={<PlayCircleOutlined />} aria-label={`运行任务 ${record.name}`} onClick={() => void handleRun(record.id)} />
            </Tooltip>
          )}
          items={[
            { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
            {
              key: 'delete',
              label: '删除',
              danger: true,
              onClick: () => modal.confirm({
                title: `删除任务“${record.name}”？`,
                content: '删除后该接入任务将无法继续运行，此操作不可恢复。',
                okText: '确认删除',
                okButtonProps: { danger: true },
                onOk: () => handleDelete(record.id)
              })
            }
          ]}
        />
      )
    })

  return (
    <>
      <PageHeader description="配置数据接入任务，覆盖数据库、API 与文件等多种数据来源。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增任务</Button> : <Tag>只读访问</Tag>} />
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item"><span>名称/目标表</span><Input placeholder="搜索名称或目标表" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="接入任务列表">
        <Table<IngestionTask> size="small" rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1000 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无接入任务' }} />
      </DataSurface>
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
