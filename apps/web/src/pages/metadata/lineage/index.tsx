import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { NodeIndexOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  createLineage,
  deleteLineage,
  listLineages,
  parseLineage,
  updateLineage,
  type LineageRelation,
  type LineageRelationInput
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

export default function LineagePage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<LineageRelation[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<LineageRelation | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<LineageRelationInput>()
  const [parseOpen, setParseOpen] = useState(false)
  const [parsing, setParsing] = useState(false)
  const [parseForm] = Form.useForm<{ sql: string; relationType?: string }>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try { setAllData(await listLineages()) } catch { message.error('血缘关系加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => { if (open) { if (editing) form.setFieldsValue(editing); else form.resetFields() } }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((r) => !keyword || r.sourceTable.includes(keyword) || r.targetTable.includes(keyword)), [allData, keyword])

  const handleSubmit = async (values: LineageRelationInput) => {
    setSubmitting(true)
    try {
      if (editing) { await updateLineage(editing.id, values); message.success('血缘关系已更新') }
      else { await createLineage(values); message.success('血缘关系已添加') }
      setOpen(false); setEditing(null); await refresh()
    } catch { message.error('保存失败') } finally { setSubmitting(false) }
  }

  const handleDelete = async (id: number) => {
    try { await deleteLineage(id); message.success('已删除'); await refresh() } catch { message.error('删除失败') }
  }

  const handleParse = async (values: { sql: string; relationType?: string }) => {
    setParsing(true)
    try {
      const result = await parseLineage(values.sql, values.relationType)
      setParseOpen(false)
      parseForm.resetFields()
      await refresh()
      if (result.created === 0 && result.statements === 0) {
        message.warning('未从 SQL 中识别出血缘关系，请确认包含 INSERT INTO / OVERWRITE 或 CREATE TABLE AS SELECT')
        return
      }
      message.success(
        `解析 ${result.statements} 条语句，新增 ${result.created} 条血缘`
          + (result.replaced > 0 ? `，替换旧关系 ${result.replaced} 条` : '')
          + (result.skipped > 0 ? `，跳过人工登记 ${result.skipped} 条` : '')
      )
    } catch {
      message.error('解析失败，请检查 SQL 内容')
    } finally {
      setParsing(false)
    }
  }

  const columns: TableColumnsType<LineageRelation> = [
    { title: '上游表', dataIndex: 'sourceTable', key: 'sourceTable', width: 200 },
    { title: '下游表', dataIndex: 'targetTable', key: 'targetTable', width: 200 },
    { title: '关系类型', dataIndex: 'relationType', key: 'relationType', width: 110, render: (v?: string) => v ? <Tag>{v}</Tag> : '—' },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 100,
      filters: [{ text: 'SQL 解析', value: 'parsed' }, { text: '人工登记', value: 'manual' }],
      onFilter: (value, record) => (record.source ?? 'manual') === value,
      render: (v?: string) => v === 'parsed' ? <Tag color="blue">SQL 解析</Tag> : <Tag>人工登记</Tag>
    },
    { title: '说明', dataIndex: 'description', key: 'description', render: (v?: string) => v || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (v?: string) => formatDateTime(v) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该关系？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="数据血缘"
        description="从加工 SQL 解析表级血缘，也支持人工登记；人工登记的关系不会被解析覆盖。"
        extra={
          <Space>
            <Button icon={<NodeIndexOutlined />} onClick={() => setParseOpen(true)}>解析 SQL</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增关系</Button>
          </Space>
        }
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item"><span>上游/下游表</span><Input placeholder="搜索表名" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>
      <Card size="small">
        <Table<LineageRelation> size="small" bordered rowKey="id" columns={columns} dataSource={filtered} loading={loading} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无血缘关系' }} />
      </Card>
      <Modal title={editing ? '编辑血缘关系' : '新增血缘关系'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting}>
        <Form form={form} layout="vertical" onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="sourceTable" label="上游表" rules={[{ required: true, message: '请输入上游表' }]}><Input /></Form.Item>
          <Form.Item name="targetTable" label="下游表" rules={[{ required: true, message: '请输入下游表' }]}><Input /></Form.Item>
          <Form.Item name="relationType" label="关系类型"><Select allowClear options={[{ value: 'etl', label: 'ETL' }, { value: 'aggregate', label: '汇总' }, { value: 'copy', label: '复制' }]} /></Form.Item>
          <Form.Item name="description" label="说明"><Input /></Form.Item>
        </Form>
      </Modal>
      <Modal
        title="从 SQL 解析血缘"
        open={parseOpen}
        onCancel={() => setParseOpen(false)}
        onOk={() => parseForm.submit()}
        confirmLoading={parsing}
        okText="开始解析"
        width={720}
      >
        <Form form={parseForm} layout="vertical" onFinish={(v) => void handleParse(v)}>
          <Form.Item
            name="sql"
            label="加工 SQL"
            rules={[{ required: true, message: '请粘贴要解析的 SQL' }]}
            extra="支持 INSERT OVERWRITE / INSERT INTO / CREATE TABLE AS SELECT，可一次粘贴多条语句。同一目标表重复解析时会替换其既有的解析结果，人工登记的关系不受影响。"
          >
            <Input.TextArea
              rows={12}
              placeholder={'INSERT OVERWRITE TABLE dwd.dwd_order PARTITION (dt)\nSELECT ... FROM ods.ods_order ...'}
              style={{ fontFamily: 'monospace', fontSize: 12 }}
            />
          </Form.Item>
          <Form.Item name="relationType" label="关系类型" extra="留空按 etl 记录">
            <Select
              allowClear
              placeholder="etl"
              options={[
                { value: 'etl', label: 'ETL' },
                { value: 'aggregate', label: '汇总' },
                { value: 'copy', label: '复制' }
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
