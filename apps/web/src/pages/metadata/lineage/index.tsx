import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { NodeIndexOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import RowActions from '@/components/RowActions'
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
import { canManagePlatform } from '@/utils/access'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'

export default function LineagePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
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
  const pageSize = useResponsivePageSize(8, 6, 5, 3)

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
      render: (v?: string) => v === 'parsed' ? <Tag>SQL 解析</Tag> : <Tag>人工登记</Tag>
    },
    { title: '说明', dataIndex: 'description', key: 'description', render: (v?: string) => v || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (v?: string) => formatDateTime(v) }
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
              title: `删除血缘“${record.sourceTable} → ${record.targetTable}”？`,
              content: '删除后该上下游关系将不再出现在平台血缘记录中。',
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
      <PageHeader
        description="基于加工 SQL 解析表级血缘，同时支持人工登记；人工登记的关系不会被解析结果覆盖。"
        extra={canManage ? (
          <Space>
            <Button icon={<NodeIndexOutlined />} onClick={() => setParseOpen(true)}>解析 SQL</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新增关系</Button>
          </Space>
        ) : <Tag>只读访问</Tag>}
      />
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item"><span>上游/下游表</span><Input placeholder="搜索表名" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" /></div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="数据血缘列表">
        <Table<LineageRelation> size="small" rowKey="id" columns={columns} dataSource={filtered} loading={loading} pagination={{ pageSize, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无血缘关系' }} />
      </DataSurface>
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
              className="code-input"
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
