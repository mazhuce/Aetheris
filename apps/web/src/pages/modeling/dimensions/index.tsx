import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ApartmentOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createSemanticDimension, deleteSemanticDimension, deprecateSemanticDimension, discardSemanticDimensionDraft, listBusinessDomains,
  listDataSourceColumns, listSemanticDimensions, listSemanticDimensionVersions, listTables,
  newSemanticDimensionVersion, updateSemanticDimension, validateSemanticDimension,
  submitSemanticReview,
  type BusinessDomain, type DataSourceColumn, type MetadataTable, type SemanticDimension,
  type SemanticDimensionInput, type SemanticVersion
} from '@/api/metadata'
import { canManagePlatform } from '@/utils/access'
import { formatDateTime } from '@/utils/format'

const typeLabels: Record<string, string> = { categorical: '分类维度', time: '时间维度', geography: '地理维度' }
const statusMeta: Record<string, { label: string; tone: 'neutral' | 'success' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' }, published: { label: '已发布', tone: 'success' }, deprecated: { label: '已废弃', tone: 'warning' }
}

export default function DimensionPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [dimensions, setDimensions] = useState<SemanticDimension[]>([])
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [columns, setColumns] = useState<DataSourceColumn[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<SemanticDimension | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [versions, setVersions] = useState<{ title: string; rows: SemanticVersion[] } | null>(null)
  const [form] = Form.useForm<SemanticDimensionInput>()
  const selectedTableId = Form.useWatch('metadataTableId', form)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [nextDimensions, nextDomains, nextTables] = await Promise.all([listSemanticDimensions(), listBusinessDomains(undefined, 'active'), listTables()])
      setDimensions(nextDimensions); setDomains(nextDomains); setTables(nextTables.filter((item) => item.status !== 'stale'))
    } catch { message.error('语义维度加载失败') } finally { setLoading(false) }
  }, [message])
  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    if (!open) return
    if (editing) form.setFieldsValue(editing)
    else { form.resetFields(); form.setFieldsValue({ dimensionType: 'categorical' }) }
  }, [editing, form, open])
  useEffect(() => {
    const table = tables.find((item) => item.id === selectedTableId)
    if (!open || !table?.datasourceId) { setColumns([]); return }
    void listDataSourceColumns(table.datasourceId, table.schemaName || '', table.tableName)
      .then(setColumns).catch(() => { setColumns([]); message.warning('暂时无法读取绑定表字段，请检查数据源连通性') })
  }, [message, open, selectedTableId, tables])

  const filtered = useMemo(() => dimensions.filter((item) =>
    (!keyword || item.dimensionName.includes(keyword) || item.dimensionCode.toLowerCase().includes(keyword.toLowerCase()))
    && (!status || item.status === status)), [dimensions, keyword, status])
  const tableName = (id: number) => tables.find((item) => item.id === id)?.tableName || `表 #${id}`
  const domainName = (id?: number) => domains.find((item) => item.id === id)?.domainName || '未归属'

  const save = async (values: SemanticDimensionInput) => {
    setSubmitting(true)
    try {
      if (editing) await updateSemanticDimension(editing.id, values)
      else await createSemanticDimension(values)
      message.success(editing ? '维度草稿已更新' : '维度草稿已创建'); setOpen(false); setEditing(null); await refresh()
    } catch (error: any) { message.error(error.response?.data?.message || '保存失败') }
    finally { setSubmitting(false) }
  }
  const run = async (action: () => Promise<unknown>, success: string) => {
    try { await action(); message.success(success); await refresh() }
    catch (error: any) { message.error(error.response?.data?.message || '操作失败') }
  }

  const columnsDef: TableColumnsType<SemanticDimension> = [
    { title: '维度编码', dataIndex: 'dimensionCode', width: 150, render: (value) => <Typography.Text code>{value}</Typography.Text> },
    { title: '维度名称', dataIndex: 'dimensionName', width: 150, render: (value) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '业务域', dataIndex: 'businessDomainId', width: 110, render: domainName },
    { title: '物理绑定', width: 230, render: (_, row) => <span>{tableName(row.metadataTableId)} · <Typography.Text code>{row.columnName}</Typography.Text></span> },
    { title: '类型', dataIndex: 'dimensionType', width: 100, render: (value) => <Tag>{typeLabels[value] || value}</Tag> },
    { title: '状态', dataIndex: 'status', width: 150, render: (value, row) => <Space size={4}><StatusBadge tone={statusMeta[value].tone}>{statusMeta[value].label}</StatusBadge>{row.publishedVersion && value === 'draft' ? <StatusBadge tone="success">线上 v{row.publishedVersion}</StatusBadge> : null}</Space> },
    { title: '版本', dataIndex: 'version', width: 80, render: (value, row) => row.publishedVersion && row.publishedVersion !== value ? `v${value} / v${row.publishedVersion}` : `v${value}` },
    { title: '负责人', dataIndex: 'owner', width: 100, render: (value) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatDateTime }
  ]
  if (canManage) columnsDef.push({ title: '操作', width: 56, fixed: 'right', align: 'right', render: (_, record) => (
    <RowActions items={[
      ...(record.status === 'draft' ? [
        { key: 'edit', label: '编辑草稿', onClick: () => { setEditing(record); setOpen(true) } },
        { key: 'validate', label: '校验绑定', onClick: () => void run(() => validateSemanticDimension(record.id), '维度校验通过') },
        { key: 'publish', label: '提交评审', onClick: () => modal.confirm({ title: `提交 ${record.dimensionCode} v${record.version} 评审？`, content: '通过复核后才可发布。', onOk: () => run(() => submitSemanticReview('semantic_dimension', record.id, `提交维度 ${record.dimensionCode} v${record.version} 评审`), '已提交评审') }) },
        ...(record.publishedVersion ? [
          { key: 'discard', label: '放弃草稿', onClick: () => modal.confirm({ title: `放弃 v${record.version} 草稿？`, content: `将恢复为线上 v${record.publishedVersion}，本次未发布修改不会保留。`, onOk: () => run(() => discardSemanticDimensionDraft(record.id), '草稿已放弃') }) },
          { key: 'deprecate', label: '下线线上版本', danger: true, onClick: () => modal.confirm({ title: '下线当前线上维度版本？', content: '依赖该维度的新查询将不再允许执行，历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateSemanticDimension(record.id), '维度线上版本已下线') }) }
        ] : [
          { key: 'delete', label: '删除草稿', danger: true, onClick: () => modal.confirm({ title: '删除维度草稿？', okButtonProps: { danger: true }, onOk: () => run(() => deleteSemanticDimension(record.id), '维度草稿已删除') }) }
        ])
      ] : [
        { key: 'version', label: '创建新版本', onClick: () => void run(() => newSemanticDimensionVersion(record.id), '已创建可编辑的新版本草稿') },
        ...(record.publishedVersion ? [{ key: 'deprecate', label: '废弃维度', danger: true, onClick: () => modal.confirm({ title: '下线当前线上维度版本？', content: '依赖该维度的新查询将不再允许执行，历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateSemanticDimension(record.id), '维度线上版本已下线') }) }] : [])
      ]),
      { key: 'history', label: '版本记录', onClick: () => void listSemanticDimensionVersions(record.id).then((rows) => setVersions({ title: record.dimensionName, rows })) }
    ]} />
  ) })

  return <>
    <PageHeader description="将业务维度绑定到真实数据字段，发布后供指标、数据集、看板和智能问数统一复用。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新建维度</Button> : <Tag>只读访问</Tag>} />
    <Row gutter={12} className="stat-row">
      <Col span={8}><StatCard title="维度总数" value={String(dimensions.length)} icon={<ApartmentOutlined />} /></Col>
      <Col span={8}><StatCard title="线上维度" value={String(dimensions.filter((item) => item.publishedVersion).length)} icon={<ApartmentOutlined />} tone="green" /></Col>
      <Col span={8}><StatCard title="覆盖数据表" value={String(new Set(dimensions.map((item) => item.metadataTableId)).size)} icon={<ApartmentOutlined />} tone="orange" /></Col>
    </Row>
    <PageToolbar><div className="filter-bar">
      <div className="filter-item"><span>名称/编码</span><Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索语义维度" className="filter-control-3xl" /></div>
      <div className="filter-item"><span>状态</span><Select allowClear value={status} onChange={setStatus} placeholder="全部状态" className="filter-control-lg" options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))} /></div>
      <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
    </div></PageToolbar>
    <DataSurface title="语义维度目录" description="发布版本不可直接编辑，确保消费方口径稳定" label="语义维度目录">
      <Table rowKey="id" size="small" loading={loading} columns={columnsDef} dataSource={filtered} scroll={{ x: 1250 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个维度` }} />
    </DataSurface>
    <Modal title={editing ? `编辑维度 · v${editing.version}` : '新建语义维度'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width={720}>
      <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
        <Row gutter={12}><Col span={12}><Form.Item name="dimensionCode" label="维度编码" rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/ }]}><Input disabled={Boolean(editing)} placeholder="如 REGION" /></Form.Item></Col><Col span={12}><Form.Item name="dimensionName" label="维度名称" rules={[{ required: true }]}><Input placeholder="如 销售区域" /></Form.Item></Col></Row>
        <Row gutter={12}><Col span={12}><Form.Item name="businessDomainId" label="业务域"><Select allowClear showSearch optionFilterProp="label" options={domains.map((item) => ({ value: item.id, label: item.domainName }))} /></Form.Item></Col><Col span={12}><Form.Item name="dimensionType" label="维度类型" rules={[{ required: true }]}><Select options={Object.entries(typeLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></Col></Row>
        <Form.Item name="metadataTableId" label="绑定数据表" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tables.map((item) => ({ value: item.id, label: `${item.datasourceName} / ${item.schemaName}.${item.tableName}` }))} /></Form.Item>
        <Form.Item name="columnName" label="维度字段" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" placeholder={selectedTableId ? '选择真实字段' : '请先选择数据表'} options={columns.map((item) => ({ value: item.name, label: `${item.name} · ${item.type}${item.remarks ? ` · ${item.remarks}` : ''}` }))} /></Form.Item>
        <Row gutter={12}><Col span={12}><Form.Item name="owner" label="负责人"><Input /></Form.Item></Col><Col span={12} /></Row>
        <Form.Item name="description" label="口径说明"><Input.TextArea rows={3} maxLength={512} showCount /></Form.Item>
      </Form>
    </Modal>
    <Modal title={`版本记录 · ${versions?.title || ''}`} open={Boolean(versions)} onCancel={() => setVersions(null)} footer={null} width={720}>
      <Table rowKey="id" size="small" dataSource={versions?.rows || []} pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 个版本` }} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value) => `v${value}` }, { title: '发布人', dataIndex: 'publishedBy' }, { title: '发布时间', dataIndex: 'publishedAt', render: formatDateTime }]} />
    </Modal>
  </>
}
