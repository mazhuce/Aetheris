import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Divider, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { MinusCircleOutlined, PartitionOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createLogicalModel, deleteLogicalModel, deprecateLogicalModel, discardLogicalModelDraft, listBusinessDomains, listLogicalModels,
  listLogicalModelVersions, listTables, newLogicalModelVersion, updateLogicalModel,
  submitSemanticReview,
  validateLogicalModel, type BusinessDomain, type LogicalModel, type MetadataTable, type SemanticVersion
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

interface ModelEntity { code: string; name: string; role: 'entity' | 'fact' | 'dimension' | 'bridge'; tableId?: number; primaryKey?: string }
interface ModelRelation { source: string; target: string; cardinality: '1:1' | '1:N' | 'N:1' | 'N:N'; joinExpression?: string }
interface ModelForm {
  modelCode: string; modelName: string; businessDomainId?: number; modelType: string; owner?: string; description?: string
  entities: ModelEntity[]; relations: ModelRelation[]
}
interface ModelGraph { entities: ModelEntity[]; relations: ModelRelation[] }

const modelTypeMeta: Record<string, { label: string; color: string }> = {
  conceptual: { label: '概念模型', color: 'default' }, relational: { label: '关系模型', color: 'blue' }, dimensional: { label: '维度模型', color: 'gold' }
}
const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' }, published: { label: '已发布', tone: 'success' }, deprecated: { label: '已废弃', tone: 'warning' }
}

function graphOf(model: LogicalModel): ModelGraph {
  try {
    const raw = typeof model.entitiesJson === 'string' ? JSON.parse(model.entitiesJson) : model.entitiesJson
    if (Array.isArray(raw)) return { entities: raw, relations: [] }
    return { entities: Array.isArray(raw?.entities) ? raw.entities : [], relations: Array.isArray(raw?.relations) ? raw.relations : [] }
  } catch { return { entities: [], relations: [] } }
}

export default function LogicalModelPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [models, setModels] = useState<LogicalModel[]>([])
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [typeFilter, setTypeFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<LogicalModel | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [versions, setVersions] = useState<{ title: string; rows: SemanticVersion[] } | null>(null)
  const [form] = Form.useForm<ModelForm>()
  const watchedEntities = Form.useWatch('entities', form) || []

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [nextModels, nextDomains, nextTables] = await Promise.all([listLogicalModels(), listBusinessDomains(undefined, 'active'), listTables()])
      setModels(nextModels); setDomains(nextDomains); setTables(nextTables.filter((item) => item.status !== 'stale'))
    } catch { message.error('逻辑模型加载失败') } finally { setLoading(false) }
  }, [message])
  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    if (!open) return
    if (editing) {
      const graph = graphOf(editing)
      form.setFieldsValue({ ...editing, entities: graph.entities, relations: graph.relations })
    } else {
      form.resetFields(); form.setFieldsValue({ modelType: 'relational', entities: [], relations: [] })
    }
  }, [editing, form, open])

  const filtered = useMemo(() => models.filter((item) =>
    (!keyword || item.modelName.includes(keyword) || item.modelCode.toLowerCase().includes(keyword.toLowerCase()) || (item.businessDomain || '').includes(keyword))
    && (!typeFilter || item.modelType === typeFilter)), [keyword, models, typeFilter])
  const run = async (action: () => Promise<unknown>, success: string) => {
    try { await action(); message.success(success); await refresh() }
    catch (error: any) { message.error(error.response?.data?.message || '操作失败') }
  }
  const save = async (values: ModelForm) => {
    setSubmitting(true)
    try {
      const payload = {
        modelCode: values.modelCode, modelName: values.modelName, businessDomainId: values.businessDomainId,
        modelType: values.modelType, owner: values.owner, description: values.description,
        entitiesJson: JSON.stringify({ entities: values.entities || [], relations: values.relations || [] })
      }
      if (editing) await updateLogicalModel(editing.id, payload)
      else await createLogicalModel(payload)
      message.success(editing ? '模型草稿已更新' : '模型草稿已创建'); setOpen(false); setEditing(null); await refresh()
    } catch (error: any) { message.error(error.response?.data?.message || '保存失败') }
    finally { setSubmitting(false) }
  }

  const columns: TableColumnsType<LogicalModel> = [
    { title: '模型编码', dataIndex: 'modelCode', width: 150, render: (value) => <Typography.Text code>{value}</Typography.Text> },
    { title: '模型名称', dataIndex: 'modelName', width: 170, render: (value) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '业务域', dataIndex: 'businessDomain', width: 110, render: (value) => value || '—' },
    { title: '模型类型', dataIndex: 'modelType', width: 100, render: (value) => <Tag color={modelTypeMeta[value]?.color}>{modelTypeMeta[value]?.label || value}</Tag> },
    { title: '实体 / 关系', width: 100, render: (_, record) => { const graph = graphOf(record); return `${graph.entities.length} / ${graph.relations.length}` } },
    { title: '状态', dataIndex: 'status', width: 150, render: (value, row) => <Space size={4}><StatusBadge tone={statusMeta[value || 'draft'].tone}>{statusMeta[value || 'draft'].label}</StatusBadge>{row.publishedVersion && value === 'draft' ? <StatusBadge tone="success">线上 v{row.publishedVersion}</StatusBadge> : null}</Space> },
    { title: '版本', dataIndex: 'version', width: 80, render: (value, row) => row.publishedVersion && row.publishedVersion !== value ? `v${value || 1} / v${row.publishedVersion}` : `v${value || 1}` },
    { title: '负责人', dataIndex: 'owner', width: 100, render: (value) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatDateTime }
  ]
  if (canManage) columns.push({ title: '操作', width: 56, fixed: 'right', align: 'right', render: (_, record) => (
    <RowActions items={[
      ...(record.status === 'draft' ? [
        { key: 'edit', label: '编辑草稿', onClick: () => { setEditing(record); setOpen(true) } },
        { key: 'validate', label: '模型校验', onClick: () => void run(() => validateLogicalModel(record.id), '模型校验通过') },
        { key: 'publish', label: '提交评审', onClick: () => modal.confirm({ title: `提交 ${record.modelCode} v${record.version} 评审？`, content: '通过复核后才可发布。', onOk: () => run(() => submitSemanticReview('logical_model', record.id, `提交模型 ${record.modelCode} v${record.version} 评审`), '已提交评审') }) },
        ...(record.publishedVersion ? [
          { key: 'discard', label: '放弃草稿', onClick: () => modal.confirm({ title: `放弃 v${record.version} 草稿？`, content: `将恢复为线上 v${record.publishedVersion}，本次未发布修改不会保留。`, onOk: () => run(() => discardLogicalModelDraft(record.id), '草稿已放弃') }) },
          { key: 'deprecate', label: '下线线上版本', danger: true, onClick: () => modal.confirm({ title: '下线当前线上模型版本？', content: '历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateLogicalModel(record.id), '模型线上版本已下线') }) }
        ] : [
          { key: 'delete', label: '删除草稿', danger: true, onClick: () => modal.confirm({ title: '删除模型草稿？', okButtonProps: { danger: true }, onOk: () => run(() => deleteLogicalModel(record.id), '模型草稿已删除') }) }
        ])
      ] : [
        { key: 'new-version', label: '创建新版本', onClick: () => void run(() => newLogicalModelVersion(record.id), '已创建可编辑的新版本草稿') },
        ...(record.publishedVersion ? [{ key: 'deprecate', label: '废弃模型', danger: true, onClick: () => modal.confirm({ title: '下线当前线上模型版本？', content: '历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateLogicalModel(record.id), '模型线上版本已下线') }) }] : [])
      ]),
      { key: 'history', label: '版本记录', onClick: () => void listLogicalModelVersions(record.id).then((rows) => setVersions({ title: record.modelName, rows })) }
    ]} />
  ) })

  const stats = { total: models.length, published: models.filter((item) => item.publishedVersion).length, entities: models.reduce((sum, item) => sum + graphOf(item).entities.length, 0) }
  const entityOptions = watchedEntities.filter((item: ModelEntity) => item?.code).map((item: ModelEntity) => ({ value: item.code.toUpperCase(), label: `${item.code.toUpperCase()} · ${item.name || ''}` }))

  return <>
    <PageHeader description="以结构化实体图描述业务对象、真实数据表和关系；发布版本作为指标、治理与服务开放的稳定契约。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新建模型</Button> : <Tag>只读访问</Tag>} />
    <Row gutter={12} className="stat-row"><Col span={8}><StatCard title="模型总数" value={String(stats.total)} icon={<PartitionOutlined />} /></Col><Col span={8}><StatCard title="已发布" value={String(stats.published)} icon={<PartitionOutlined />} tone="green" /></Col><Col span={8}><StatCard title="纳管实体" value={String(stats.entities)} icon={<PartitionOutlined />} tone="orange" /></Col></Row>
    <PageToolbar><div className="filter-bar"><div className="filter-item"><span>名称/编码/域</span><Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} className="filter-control-3xl" placeholder="搜索逻辑模型" /></div><div className="filter-item"><span>模型类型</span><Select allowClear value={typeFilter} onChange={setTypeFilter} className="filter-control-xl" placeholder="全部类型" options={Object.entries(modelTypeMeta).map(([value, meta]) => ({ value, label: meta.label }))} /></div><Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button></div></PageToolbar>
    <DataSurface title="逻辑模型目录" description="发布版本不可直接编辑，变更必须创建新版本" label="逻辑模型目录"><Table rowKey="id" size="small" loading={loading} columns={columns} dataSource={filtered} scroll={{ x: 1200 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个模型` }} /></DataSurface>

    <Modal title={editing ? `编辑模型 · v${editing.version}` : '新建逻辑模型'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width="min(1120px, 94vw)" destroyOnClose>
      <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
        <Row gutter={12}><Col span={6}><Form.Item name="modelCode" label="模型编码" rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/ }]}><Input disabled={Boolean(editing)} placeholder="TRADE_ORDER" /></Form.Item></Col><Col span={6}><Form.Item name="modelName" label="模型名称" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={6}><Form.Item name="businessDomainId" label="业务域"><Select allowClear options={domains.map((item) => ({ value: item.id, label: item.domainName }))} /></Form.Item></Col><Col span={6}><Form.Item name="modelType" label="模型类型" rules={[{ required: true }]}><Select options={Object.entries(modelTypeMeta).map(([value, meta]) => ({ value, label: meta.label }))} /></Form.Item></Col></Row>
        <Row gutter={12}><Col span={8}><Form.Item name="owner" label="负责人"><Input /></Form.Item></Col><Col span={16}><Form.Item name="description" label="模型目标"><Input maxLength={512} /></Form.Item></Col></Row>
        <Divider orientation="left">实体与物理表绑定</Divider>
        <Form.List name="entities">{(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
          {fields.map((field) => <Row gutter={8} key={field.key} align="middle"><Col span={4}><Form.Item {...field} name={[field.name, 'code']} rules={[{ required: true }]}><Input placeholder="实体编码" /></Form.Item></Col><Col span={4}><Form.Item {...field} name={[field.name, 'name']} rules={[{ required: true }]}><Input placeholder="实体名称" /></Form.Item></Col><Col span={4}><Form.Item {...field} name={[field.name, 'role']} initialValue="entity"><Select options={[{ value: 'entity', label: '业务实体' }, { value: 'fact', label: '事实实体' }, { value: 'dimension', label: '维度实体' }, { value: 'bridge', label: '桥接实体' }]} /></Form.Item></Col><Col span={7}><Form.Item {...field} name={[field.name, 'tableId']}><Select allowClear showSearch optionFilterProp="label" placeholder="绑定数据表" options={tables.map((item) => ({ value: item.id, label: `${item.schemaName}.${item.tableName}` }))} /></Form.Item></Col><Col span={4}><Form.Item {...field} name={[field.name, 'primaryKey']}><Input placeholder="主键字段" /></Form.Item></Col><Col span={1}><Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} /></Col></Row>)}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ role: 'entity' })}>添加实体</Button>
        </Space>}</Form.List>
        <Divider orientation="left">实体关系</Divider>
        <Form.List name="relations">{(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
          {fields.map((field) => <Row gutter={8} key={field.key} align="middle"><Col span={5}><Form.Item {...field} name={[field.name, 'source']} rules={[{ required: true }]}><Select placeholder="来源实体" options={entityOptions} /></Form.Item></Col><Col span={5}><Form.Item {...field} name={[field.name, 'target']} rules={[{ required: true }]}><Select placeholder="目标实体" options={entityOptions} /></Form.Item></Col><Col span={4}><Form.Item {...field} name={[field.name, 'cardinality']} initialValue="N:1"><Select options={['1:1', '1:N', 'N:1', 'N:N'].map((value) => ({ value, label: value }))} /></Form.Item></Col><Col span={9}><Form.Item {...field} name={[field.name, 'joinExpression']}><Input placeholder="如 ORDER.customer_id = CUSTOMER.id" /></Form.Item></Col><Col span={1}><Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} /></Col></Row>)}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ cardinality: 'N:1' })} disabled={entityOptions.length < 2}>添加关系</Button>
        </Space>}</Form.List>
      </Form>
    </Modal>
    <Modal title={`版本记录 · ${versions?.title || ''}`} open={Boolean(versions)} onCancel={() => setVersions(null)} footer={null} width={720}><Table rowKey="id" size="small" dataSource={versions?.rows || []} pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 个版本` }} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value) => `v${value}` }, { title: '发布人', dataIndex: 'publishedBy' }, { title: '发布时间', dataIndex: 'publishedAt', render: formatDateTime }]} /></Modal>
  </>
}
