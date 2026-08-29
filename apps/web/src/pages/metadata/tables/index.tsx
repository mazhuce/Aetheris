import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, InputNumber, Modal, Popconfirm, Select, Space, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { CloudSyncOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  createTable,
  deleteTable,
  listDataSources,
  listTables,
  syncMetadataTables,
  updateTable,
  warehouseLayerLabel,
  WAREHOUSE_LAYERS,
  type DataSource,
  type MetadataTable,
  type MetadataTableInput,
  type WarehouseLayer
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

/** 分层配色：按数据流向由浅入深，与数仓地图页保持一致。 */
const LAYER_COLORS: Record<string, string> = {
  ods: 'cyan',
  dwd: 'blue',
  dws: 'geekblue',
  ads: 'purple',
  dim: 'orange',
  other: 'default'
}

export default function MetadataTablePage() {
  const { message, modal } = App.useApp()
  const [allData, setAllData] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [layerFilter, setLayerFilter] = useState<WarehouseLayer | undefined>()
  const [domainFilter, setDomainFilter] = useState<string | undefined>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<MetadataTable | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<MetadataTableInput>()
  const [syncOpen, setSyncOpen] = useState(false)
  const [syncing, setSyncing] = useState(false)
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [syncForm] = Form.useForm<{ datasourceId: number; schemaName?: string }>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try { setAllData(await listTables()) } catch { message.error('元数据目录加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => { if (open) { if (editing) form.setFieldsValue(editing); else form.resetFields() } }, [open, editing, form])

  const openSync = async () => {
    setSyncOpen(true)
    syncForm.resetFields()
    try { setDataSources(await listDataSources()) } catch { message.error('数据源列表加载失败') }
  }

  const handleSync = async (values: { datasourceId: number; schemaName?: string }) => {
    setSyncing(true)
    try {
      const result = await syncMetadataTables(values.datasourceId, values.schemaName?.trim() || undefined)
      setSyncOpen(false)
      await refresh()
      modal.info({
        title: '采集完成',
        content: (
          <div>
            <div>数据源：{result.datasourceName}</div>
            <div>采集范围：{result.schemas.join('、') || '—'}</div>
            <div>发现表 {result.scanned} 张：新增 {result.created}、更新 {result.updated}、标记消失 {result.removed}、跳过手工登记 {result.skipped}</div>
            {result.failures.length > 0 && (
              <div style={{ marginTop: 8, color: '#d4380d' }}>部分库采集失败：{result.failures.join('；')}</div>
            )}
          </div>
        )
      })
    } catch {
      message.error('采集失败，请检查数据源连通性')
    } finally {
      setSyncing(false)
    }
  }

  const domainOptions = useMemo(() => {
    const domains = new Set<string>()
    allData.forEach((t) => { if (t.domain) domains.add(t.domain) })
    return Array.from(domains).sort().map((d) => ({ value: d, label: d }))
  }, [allData])

  const filtered = useMemo(
    () =>
      allData.filter((t) => {
        if (keyword && !t.tableName.includes(keyword) && !t.datasourceName.includes(keyword)) return false
        // layer 为空的历史记录按「未分层」处理，与后端数仓地图的归类保持一致。
        if (layerFilter && (t.layer ?? 'other') !== layerFilter) return false
        if (domainFilter && t.domain !== domainFilter) return false
        return true
      }),
    [allData, keyword, layerFilter, domainFilter]
  )

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
    {
      title: '分层',
      dataIndex: 'layer',
      key: 'layer',
      width: 100,
      render: (v?: string, record?: MetadataTable) => {
        const layer = v ?? 'other'
        const color = LAYER_COLORS[layer] ?? 'default'
        // 人工指定过的层级加下划线提示，避免误以为是采集推断的结果。
        return (
          <Tag color={color} style={record?.layerSource === 'manual' ? { borderStyle: 'dashed' } : undefined}>
            {warehouseLayerLabel(layer)}
          </Tag>
        )
      }
    },
    { title: '业务域', dataIndex: 'domain', key: 'domain', width: 120, render: (v?: string) => v || '—' },
    { title: 'Schema', dataIndex: 'schemaName', key: 'schemaName', width: 110, render: (v?: string) => v || '—' },
    { title: '表名', dataIndex: 'tableName', key: 'tableName', width: 150 },
    { title: '说明', dataIndex: 'tableComment', key: 'tableComment', render: (v?: string) => v || '—' },
    { title: '字段数', dataIndex: 'columnCount', key: 'columnCount', width: 90, render: (v?: number) => v ?? '—' },
    {
      title: '来源',
      dataIndex: 'source',
      key: 'source',
      width: 100,
      filters: [{ text: '自动采集', value: 'auto' }, { text: '手工登记', value: 'manual' }],
      onFilter: (value, record) => (record.source ?? 'manual') === value,
      render: (v?: string) => v === 'auto' ? <Tag color="blue">自动采集</Tag> : <Tag>手工登记</Tag>
    },
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 100, render: (v?: string) => v || '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v?: string) => {
        if (v === 'active') return <Tag color="success">正常</Tag>
        if (v === 'stale') return <Tag color="warning">已消失</Tag>
        return <Tag>停用</Tag>
      }
    },
    { title: '采集时间', dataIndex: 'syncedAt', key: 'syncedAt', width: 170, render: (v?: string) => formatDateTime(v) },
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
      <PageHeader
        title="元数据目录"
        description="从数据源自动采集库表元数据，也支持手工登记；手工登记的记录不会被采集覆盖。"
        extra={
          <Space>
            <Button icon={<CloudSyncOutlined />} onClick={() => void openSync()}>采集元数据</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>登记表</Button>
          </Space>
        }
      />
      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item"><span>数据源/表名</span><Input placeholder="搜索数据源或表名" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} /></div>
          <div className="filter-item">
            <span>分层</span>
            <Select
              placeholder="全部分层"
              value={layerFilter}
              onChange={setLayerFilter}
              allowClear
              style={{ width: 130 }}
              options={WAREHOUSE_LAYERS.map((l) => ({ value: l.value, label: l.label }))}
            />
          </div>
          <div className="filter-item">
            <span>业务域</span>
            <Select
              placeholder="全部业务域"
              value={domainFilter}
              onChange={setDomainFilter}
              allowClear
              style={{ width: 150 }}
              options={domainOptions}
              notFoundContent="暂无业务域"
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>
      <Card size="small">
        <Table<MetadataTable> size="small" bordered rowKey="id" columns={columns} dataSource={filtered} loading={loading} scroll={{ x: 1280 }} pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }} locale={{ emptyText: '暂无元数据' }} />
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
            <Select options={[{ value: 'active', label: '正常' }, { value: 'inactive', label: '停用' }, { value: 'stale', label: '已消失' }]} />
          </Form.Item>
        </Form>
      </Modal>
      <Modal
        title="采集元数据"
        open={syncOpen}
        onCancel={() => setSyncOpen(false)}
        onOk={() => syncForm.submit()}
        confirmLoading={syncing}
        okText="开始采集"
        width={520}
      >
        <Form form={syncForm} layout="vertical" onFinish={(v) => void handleSync(v)}>
          <Form.Item name="datasourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
            <Select
              placeholder="选择要采集的数据源"
              options={dataSources.map((d) => ({ value: d.id, label: `${d.name}（${d.type}）` }))}
            />
          </Form.Item>
          <Form.Item
            name="schemaName"
            label="库 / Schema"
            extra="留空则采集该数据源下全部非系统库，表数量多时耗时较长。"
          >
            <Input placeholder="例如：public，留空采集全部" allowClear />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
