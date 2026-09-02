import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, InputNumber, Modal, Select, Table, Tag, Tooltip } from 'antd'
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
import { listDataSources, type DataSource } from '@/api/metadata'

export default function IngestionPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<IngestionTask[]>([])
  const [dataSources, setDataSources] = useState<DataSource[]>([])
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

  useEffect(() => {
    void refresh()
    void listDataSources().then(setDataSources).catch(() => message.error('数据源列表加载失败'))
  }, [refresh, message])
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
    try {
      const run = await runTask(id)
      message.success(`接入完成，写入 ${run.rowsWritten ?? 0} 行`)
      await refresh()
    } catch { message.error('运行失败，请查看任务错误信息') }
  }

  const sourceName = (id?: number) => dataSources.find((source) => source.id === id)?.name ?? '待配置'

  const columns: TableColumnsType<IngestionTask> = [
    { title: '任务名称', dataIndex: 'name', key: 'name', width: 180 },
    { title: '来源数据源', dataIndex: 'sourceDatasourceId', key: 'sourceDatasourceId', width: 150, render: (v?: number) => sourceName(v) },
    { title: '目标数据源', dataIndex: 'targetDatasourceId', key: 'targetDatasourceId', width: 150, render: (v?: number) => sourceName(v) },
    { title: '目标表', dataIndex: 'targetTable', key: 'targetTable', width: 150 },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => <StatusBadge tone={v === 'enabled' ? 'success' : 'neutral'}>{v === 'enabled' ? '启用' : '停用'}</StatusBadge> },
    { title: '上次结果', dataIndex: 'lastRunStatus', key: 'lastRunStatus', width: 100, render: (v?: string) => v ? <StatusBadge tone={v === 'success' ? 'success' : 'danger'}>{v === 'success' ? '成功' : '失败'}</StatusBadge> : '—' },
    { title: '写入行数', dataIndex: 'lastRunRows', key: 'lastRunRows', width: 100, render: (v?: number) => v ?? '—' },
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
              <Button
                type="text"
                size="small"
                icon={<PlayCircleOutlined />}
                aria-label={`运行任务 ${record.name}`}
                disabled={!record.sourceDatasourceId || !record.targetDatasourceId || !record.sourceSql}
                onClick={() => void handleRun(record.id)}
              />
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
      <PageHeader description="配置关系型数据源之间的批量接入任务，并查看每次真实执行结果。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增任务</Button> : <Tag>只读访问</Tag>} />
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
        <Form form={form} layout="vertical" initialValues={{ sourceType: 'jdbc', status: 'enabled', writeMode: 'append', batchSize: 500 }} onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="name" label="任务名称" rules={[{ required: true, message: '请输入名称' }]}><Input /></Form.Item>
          <Form.Item name="sourceDatasourceId" label="来源数据源" rules={[{ required: true, message: '请选择来源数据源' }]}>
            <Select showSearch optionFilterProp="label" options={dataSources.map((source) => ({ value: source.id, label: `${source.name} · ${source.type}` }))} />
          </Form.Item>
          <Form.Item name="targetDatasourceId" label="目标数据源" rules={[{ required: true, message: '请选择目标数据源' }]} extra="当前执行器支持 PostgreSQL/MySQL 兼容目标。">
            <Select showSearch optionFilterProp="label" options={dataSources.filter((source) => ['postgresql', 'greenplum', 'opengauss', 'cockroachdb', 'yugabytedb', 'mysql', 'mariadb', 'tidb', 'oceanbase', 'doris', 'starrocks'].includes(source.type)).map((source) => ({ value: source.id, label: `${source.name} · ${source.type}` }))} />
          </Form.Item>
          <Form.Item name="sourceSql" label="来源 SQL" rules={[{ required: true, message: '请输入只读来源 SQL' }]}>
            <Input.TextArea rows={5} placeholder="SELECT id, created_at, amount FROM public.orders" />
          </Form.Item>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: true, message: '请输入目标表' }]}><Input placeholder="例如：ods_orders" /></Form.Item>
          <Form.Item name="writeMode" label="写入模式" rules={[{ required: true }]}>
            <Select options={[{ value: 'append', label: '追加' }, { value: 'replace', label: '全量替换（事务内）' }]} />
          </Form.Item>
          <Form.Item name="batchSize" label="批次大小" rules={[{ required: true }]}>
            <InputNumber min={1} max={5000} className="full-width-control" />
          </Form.Item>
          <Form.Item name="status" label="状态"><Select options={[{ value: 'enabled', label: '启用' }, { value: 'disabled', label: '停用' }]} /></Form.Item>
          <Form.Item name="scheduleCron" label="定时调度" extra="Quartz cron（6/7 段），例如 0 0 2 * * ? 表示每天凌晨 2 点；留空表示仅手动执行。"><Input placeholder="例如：0 0 2 * * ?" /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
        </Form>
      </Modal>
    </>
  )
}
