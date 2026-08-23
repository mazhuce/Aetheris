import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import { createTable, deleteTable, listTables, updateTable, type MetadataTable, type MetadataTableInput } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

export default function MetadataTablePage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<MetadataTable | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<MetadataTableInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try { setAllData(await listTables()) } catch { message.error('元数据目录加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => { if (open) { if (editing) form.setFieldsValue(editing); else form.resetFields() } }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((t) => !keyword || t.tableName.includes(keyword) || t.datasourceName.includes(keyword)), [allData, keyword])

  const handleSubmit = async (values: MetadataTableInput) => {
    setSubmitting(true)
    try {
      if (editing) { await updateTable(editing.id, values); message.success('表信息已更新') }
      else { await createTable(values); message.success('表信息已添加') }
      setOpen(false); setEditing(null); await refresh()
    } catch { message.error('保存失败') } finally { setSubmitting(false) }
  }

  const handleDelete = async (id: number) => {
    try { await deleteTable(id); message.success('已删除'); await refresh() } catch { message.error('删除失败') }
  }

  const columns: TableColumnsType<MetadataTable> = [
    { title: '数据源', dataIndex: 'datasourceName', key: 'datasourceName', width: 150 },
    { title: 'Schema', dataIndex: 'schemaName', key: 'schemaName', width: 120, render: (v?: string) => v || '—' },
    { title: '表名', dataIndex: 'tableName', key: 'tableName', width: 150 },
    { title: '说明', dataIndex: 'tableComment', key: 'tableComment', render: (v?: string) => v || '—' },
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 100, render: (v?: string) => v || '—' },
    { title: '行数', dataIndex: 'rowCount', key: 'rowCount', width: 110, render: (v?: number) => v ?? '—' },
    { title: '状态', dataIndex: 'status', key: 'status', width: 90, render: (v?: string) => v === 'active' ? <Tag color="success">正常</Tag> : <Tag>停用</Tag> },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (v?: string) => formatDateTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该表信息？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader title="元数据目录" description="管理已纳管的库表元数据信息。" extra={<Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>登记表</Button>} />
      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item"><span>数据源/表名</span><Input placeholder="搜索数据源或表名" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>
      <Card size="small">
        <Table<MetadataTable> size="small" bordered rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1100 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无元数据' }} />
      </Card>
      <Modal title={editing ? '编辑表信息' : '登记表'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width={560}>
        <Form form={form} layout="vertical" initialValues={{ status: 'active' }} onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="datasourceName" label="数据源" rules={[{ required: true, message: '请输入数据源' }]}><Input /></Form.Item>
          <Form.Item name="schemaName" label="Schema"><Input placeholder="例如：public" /></Form.Item>
          <Form.Item name="tableName" label="表名" rules={[{ required: true, message: '请输入表名' }]}><Input /></Form.Item>
          <Form.Item name="tableComment" label="说明"><Input /></Form.Item>
          <Form.Item name="owner" label="负责人"><Input /></Form.Item>
          <Form.Item name="rowCount" label="行数"><InputNumber min={0} style={{ width: '100%' }} /></Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 'active', label: '正常' }, { value: 'inactive', label: '停用' }]} />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
