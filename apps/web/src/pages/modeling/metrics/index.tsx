import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, DatePicker, Divider, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { MinusCircleOutlined, DotChartOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createMetricDefinition, deleteMetricDefinition, deprecateMetricDefinition, discardMetricDefinitionDraft, listBusinessDomains,
  listDataSourceColumns, listLogicalModels, listMetricDefinitions, listMetricDefinitionVersions,
  listSemanticDimensions, listTables, newMetricDefinitionVersion, previewMetricDefinition,
  updateMetricDefinition, validateMetricDefinition,
  submitSemanticReview,
  type BusinessDomain, type DataSourceColumn, type LogicalModel, type MetadataTable,
  type MetricDefinition, type SemanticDimension, type SemanticMetricQueryResult, type SemanticVersion
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

interface FilterForm { column: string; operator: string; value?: string }
interface MetricForm {
  metricCode: string; metricName: string; businessDomainId?: number; logicalModelId?: number; sourceTableId: number
  layer: string; aggregation: string; measureColumn?: string; filters?: FilterForm[]; timeColumn?: string
  defaultTimeGrain: string; dimensionIds?: number[]; calibreFormula?: string; unit?: string; owner?: string; description?: string
}

const layerLabels: Record<string, string> = { atomic: '原子指标', derived: '派生指标', composite: '复合指标' }
const statusMeta: Record<string, { label: string; tone: 'neutral' | 'success' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' }, published: { label: '已发布', tone: 'success' }, deprecated: { label: '已废弃', tone: 'warning' }
}
const aggregationLabels: Record<string, string> = { SUM: '求和 SUM', COUNT: '计数 COUNT', COUNT_DISTINCT: '去重计数', AVG: '平均值 AVG', MIN: '最小值 MIN', MAX: '最大值 MAX' }
const filterLabels: Record<string, string> = { eq: '等于', ne: '不等于', gt: '大于', gte: '大于等于', lt: '小于', lte: '小于等于', in: '属于 IN', not_in: '不属于', is_null: '为空', is_not_null: '不为空' }

function parseIds(raw?: string) { try { const value = JSON.parse(raw || '[]'); return Array.isArray(value) ? value.filter(Number.isFinite) : [] } catch { return [] } }
function parseFilters(raw?: string): FilterForm[] { try { const value = JSON.parse(raw || '[]'); return Array.isArray(value) ? value.map((item) => ({ ...item, value: Array.isArray(item.value) ? item.value.join(',') : String(item.value ?? '') })) : [] } catch { return [] } }
function resultRows(result: SemanticMetricQueryResult) {
  return result.rows.map((row, index) => Object.fromEntries([['key', index], ...result.columns.map((column, columnIndex) => [column, row[columnIndex]])]))
}

export default function MetricManagePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [metrics, setMetrics] = useState<MetricDefinition[]>([])
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [models, setModels] = useState<LogicalModel[]>([])
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [dimensions, setDimensions] = useState<SemanticDimension[]>([])
  const [sourceColumns, setSourceColumns] = useState<DataSourceColumn[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<MetricDefinition | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [versions, setVersions] = useState<{ title: string; rows: SemanticVersion[] } | null>(null)
  const [previewing, setPreviewing] = useState<MetricDefinition | null>(null)
  const [preview, setPreview] = useState<SemanticMetricQueryResult | null>(null)
  const [querying, setQuerying] = useState(false)
  const [form] = Form.useForm<MetricForm>()
  const [previewForm] = Form.useForm<{ dates?: [any, any]; timeGrain?: 'none' | 'day' | 'month' | 'year'; dimensions?: string[] }>()
  const selectedTableId = Form.useWatch('sourceTableId', form)
  const aggregation = Form.useWatch('aggregation', form)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [nextMetrics, nextDomains, nextModels, nextTables, nextDimensions] = await Promise.all([
        listMetricDefinitions(), listBusinessDomains(undefined, 'active'), listLogicalModels(undefined, 'published'), listTables(), listSemanticDimensions(undefined, 'published')
      ])
      setMetrics(nextMetrics); setDomains(nextDomains); setModels(nextModels)
      setTables(nextTables.filter((item) => item.status !== 'stale')); setDimensions(nextDimensions)
    } catch { message.error('指标目录加载失败') } finally { setLoading(false) }
  }, [message])
  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    if (!open) return
    if (editing) form.setFieldsValue({
      ...editing, filters: parseFilters(editing.filtersJson), dimensionIds: parseIds(editing.dimensionIdsJson),
      aggregation: editing.aggregation || 'COUNT', defaultTimeGrain: editing.defaultTimeGrain || 'none'
    } as MetricForm)
    else { form.resetFields(); form.setFieldsValue({ layer: 'atomic', aggregation: 'COUNT', defaultTimeGrain: 'none', filters: [], dimensionIds: [] } as Partial<MetricForm>) }
  }, [editing, form, open])
  useEffect(() => {
    const table = tables.find((item) => item.id === selectedTableId)
    if (!open || !table?.datasourceId) { setSourceColumns([]); return }
    void listDataSourceColumns(table.datasourceId, table.schemaName || '', table.tableName).then(setSourceColumns)
      .catch(() => { setSourceColumns([]); message.warning('无法读取来源表字段，请检查数据源连通性') })
  }, [message, open, selectedTableId, tables])

  const filtered = useMemo(() => metrics.filter((item) =>
    (!keyword || item.metricName.includes(keyword) || item.metricCode.toLowerCase().includes(keyword.toLowerCase()) || (item.businessDomain || '').includes(keyword))
    && (!status || item.status === status)), [keyword, metrics, status])
  const tableName = (id?: number) => tables.find((item) => item.id === id)?.tableName || (id ? `表 #${id}` : '未绑定')
  const run = async (action: () => Promise<unknown>, success: string) => {
    try { await action(); message.success(success); await refresh() }
    catch (error: any) { message.error(error.response?.data?.message || '操作失败') }
  }
  const save = async (values: MetricForm) => {
    setSubmitting(true)
    try {
      const payload = {
        ...values,
        filtersJson: JSON.stringify((values.filters || []).map((item) => ({ ...item, value: item.operator.startsWith('is_') ? null : item.operator.includes('in') ? (item.value || '').split(',').map((value) => value.trim()).filter(Boolean) : item.value }))),
        dimensionIdsJson: JSON.stringify(values.dimensionIds || [])
      }
      delete (payload as any).filters; delete (payload as any).dimensionIds
      if (editing) await updateMetricDefinition(editing.id, payload)
      else await createMetricDefinition(payload)
      message.success(editing ? '指标草稿已更新' : '指标草稿已创建'); setOpen(false); setEditing(null); await refresh()
    } catch (error: any) { message.error(error.response?.data?.message || '保存失败') }
    finally { setSubmitting(false) }
  }
  const executePreview = async () => {
    if (!previewing) return
    const values = previewForm.getFieldsValue()
    setQuerying(true)
    try {
      const result = await previewMetricDefinition(previewing.id, {
        dimensions: values.dimensions, timeGrain: values.timeGrain,
        startDate: values.dates?.[0]?.format('YYYY-MM-DD'), endDate: values.dates?.[1]?.format('YYYY-MM-DD')
      })
      setPreview(result)
    } catch (error: any) { message.error(error.response?.data?.message || '指标试算失败') }
    finally { setQuerying(false) }
  }

  const tableColumns: TableColumnsType<MetricDefinition> = [
    { title: '指标编码', dataIndex: 'metricCode', width: 150, render: (value) => <Typography.Text code>{value}</Typography.Text> },
    { title: '指标名称', dataIndex: 'metricName', width: 160, render: (value) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '业务域', dataIndex: 'businessDomain', width: 110, render: (value) => value || '—' },
    { title: '来源表', dataIndex: 'sourceTableId', width: 150, render: tableName },
    { title: '计算', width: 170, render: (_, row) => row.aggregation ? `${aggregationLabels[row.aggregation] || row.aggregation}${row.measureColumn ? ` · ${row.measureColumn}` : ''}` : '待补全' },
    { title: '状态', dataIndex: 'status', width: 150, render: (value, row) => <Space size={4}><StatusBadge tone={statusMeta[value || 'draft'].tone}>{statusMeta[value || 'draft'].label}</StatusBadge>{row.publishedVersion && value === 'draft' ? <StatusBadge tone="success">线上 v{row.publishedVersion}</StatusBadge> : null}</Space> },
    { title: '版本', dataIndex: 'version', width: 80, render: (value, row) => row.publishedVersion && row.publishedVersion !== value ? `v${value || 1} / v${row.publishedVersion}` : `v${value || 1}` },
    { title: '负责人', dataIndex: 'owner', width: 100, render: (value) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatDateTime }
  ]
  if (canManage) tableColumns.push({ title: '操作', width: 56, fixed: 'right', align: 'right', render: (_, record) => (
    <RowActions items={[
      { key: 'preview', label: '指标试算', onClick: () => { setPreviewing(record); setPreview(null); previewForm.resetFields(); previewForm.setFieldsValue({ timeGrain: record.defaultTimeGrain || 'none', dimensions: dimensions.filter((item) => parseIds(record.dimensionIdsJson).includes(item.id)).map((item) => item.dimensionCode) }) } },
      ...(record.status === 'draft' ? [
        { key: 'edit', label: '编辑草稿', onClick: () => { setEditing(record); setOpen(true) } },
        { key: 'validate', label: '口径校验', onClick: () => void run(() => validateMetricDefinition(record.id), '指标口径校验通过') },
        { key: 'publish', label: '提交评审', onClick: () => modal.confirm({ title: `提交 ${record.metricCode} v${record.version} 评审？`, content: '提交后由具备发布权限的复核人审批，通过后再执行发布。', onOk: () => run(() => submitSemanticReview('metric_definition', record.id, `提交指标 ${record.metricCode} v${record.version} 评审`), '已提交评审') }) },
        ...(record.publishedVersion ? [
          { key: 'discard', label: '放弃草稿', onClick: () => modal.confirm({ title: `放弃 v${record.version} 草稿？`, content: `将恢复为线上 v${record.publishedVersion}，本次未发布修改不会保留。`, onOk: () => run(() => discardMetricDefinitionDraft(record.id), '草稿已放弃') }) },
          { key: 'deprecate', label: '下线线上版本', danger: true, onClick: () => modal.confirm({ title: '下线当前线上指标版本？', content: '依赖它的新查询将停止执行，历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateMetricDefinition(record.id), '指标线上版本已下线') }) }
        ] : [
          { key: 'delete', label: '删除草稿', danger: true, onClick: () => modal.confirm({ title: '删除指标草稿？', okButtonProps: { danger: true }, onOk: () => run(() => deleteMetricDefinition(record.id), '指标草稿已删除') }) }
        ])
      ] : [
        { key: 'new-version', label: '创建新版本', onClick: () => void run(() => newMetricDefinitionVersion(record.id), '已创建可编辑的新版本草稿') },
        ...(record.publishedVersion ? [{ key: 'deprecate', label: '废弃指标', danger: true, onClick: () => modal.confirm({ title: '下线当前线上指标版本？', content: '依赖它的新查询将停止执行，历史快照仍会保留。', okButtonProps: { danger: true }, onOk: () => run(() => deprecateMetricDefinition(record.id), '指标线上版本已下线') }) }] : [])
      ]),
      { key: 'history', label: '版本记录', onClick: () => void listMetricDefinitionVersions(record.id).then((rows) => setVersions({ title: record.metricName, rows })) }
    ]} />
  ) })

  const compatibleDimensions = dimensions.filter((item) => item.metadataTableId === selectedTableId)
  const stats = { total: metrics.length, published: metrics.filter((item) => item.publishedVersion).length, executable: metrics.filter((item) => item.sourceTableId && item.aggregation).length }
  return <>
    <PageHeader description="以受控聚合、结构化过滤、时间粒度和正式维度定义企业指标；同一发布版本供数据集、看板与智能问数共同消费。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新建指标</Button> : <Tag>只读访问</Tag>} />
    <Row gutter={12} className="stat-row"><Col span={8}><StatCard title="指标总数" value={String(stats.total)} icon={<DotChartOutlined />} /></Col><Col span={8}><StatCard title="已发布" value={String(stats.published)} icon={<DotChartOutlined />} tone="green" /></Col><Col span={8}><StatCard title="可执行口径" value={String(stats.executable)} icon={<DotChartOutlined />} tone="orange" /></Col></Row>
    <PageToolbar><div className="filter-bar"><div className="filter-item"><span>名称/编码/域</span><Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} className="filter-control-3xl" placeholder="搜索指标" /></div><div className="filter-item"><span>状态</span><Select allowClear value={status} onChange={setStatus} className="filter-control-xl" placeholder="全部状态" options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))} /></div><Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button></div></PageToolbar>
    <DataSurface title="企业指标目录" description="发布后口径不可变；调整必须创建新版本" label="企业指标目录"><Table rowKey="id" size="small" loading={loading} columns={tableColumns} dataSource={filtered} scroll={{ x: 1250 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个指标` }} /></DataSurface>

    <Modal title={editing ? `编辑指标 · v${editing.version}` : '新建企业指标'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width="min(1020px, 94vw)" destroyOnClose>
      <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
        <Row gutter={12}><Col span={6}><Form.Item name="metricCode" label="指标编码" rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/ }]}><Input disabled={Boolean(editing)} placeholder="GMV_DAILY" /></Form.Item></Col><Col span={6}><Form.Item name="metricName" label="指标名称" rules={[{ required: true }]}><Input /></Form.Item></Col><Col span={6}><Form.Item name="businessDomainId" label="业务域"><Select allowClear options={domains.map((item) => ({ value: item.id, label: item.domainName }))} /></Form.Item></Col><Col span={6}><Form.Item name="layer" label="指标层级"><Select options={Object.entries(layerLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></Col></Row>
        <Row gutter={12}><Col span={8}><Form.Item name="logicalModelId" label="逻辑模型"><Select allowClear placeholder="选择已发布模型" options={models.map((item) => ({ value: item.id, label: `${item.modelCode} · ${item.modelName}` }))} /></Form.Item></Col><Col span={8}><Form.Item name="sourceTableId" label="指标来源表" rules={[{ required: true }]}><Select showSearch optionFilterProp="label" options={tables.map((item) => ({ value: item.id, label: `${item.datasourceName} / ${item.schemaName}.${item.tableName}` }))} /></Form.Item></Col><Col span={8}><Form.Item name="owner" label="负责人"><Input /></Form.Item></Col></Row>
        <Divider orientation="left">可执行计算口径</Divider>
        <Row gutter={12}><Col span={8}><Form.Item name="aggregation" label="聚合方式" rules={[{ required: true }]}><Select options={Object.entries(aggregationLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></Col><Col span={8}><Form.Item name="measureColumn" label="度量字段" rules={aggregation === 'COUNT' ? [] : [{ required: true }]}><Select allowClear showSearch optionFilterProp="label" placeholder={aggregation === 'COUNT' ? '留空表示 COUNT(*)' : '选择数值字段'} options={sourceColumns.map((item) => ({ value: item.name, label: `${item.name} · ${item.type}` }))} /></Form.Item></Col><Col span={8}><Form.Item name="unit" label="单位"><Input placeholder="元、个、%" /></Form.Item></Col></Row>
        <Form.List name="filters">{(fields, { add, remove }) => <Space direction="vertical" style={{ width: '100%' }}>
          {fields.map((field) => <Row gutter={8} key={field.key} align="middle"><Col span={8}><Form.Item {...field} name={[field.name, 'column']} rules={[{ required: true }]}><Select placeholder="过滤字段" options={sourceColumns.map((item) => ({ value: item.name, label: item.name }))} /></Form.Item></Col><Col span={6}><Form.Item {...field} name={[field.name, 'operator']} initialValue="eq"><Select options={Object.entries(filterLabels).map(([value, label]) => ({ value, label }))} /></Form.Item></Col><Col span={9}><Form.Item {...field} name={[field.name, 'value']}><Input placeholder="IN 多值用逗号分隔" /></Form.Item></Col><Col span={1}><Button type="text" danger icon={<MinusCircleOutlined />} onClick={() => remove(field.name)} /></Col></Row>)}
          <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ operator: 'eq' })}>添加固定过滤条件</Button>
        </Space>}</Form.List>
        <Divider orientation="left">时间与分析维度</Divider>
        <Row gutter={12}><Col span={8}><Form.Item name="timeColumn" label="时间字段"><Select allowClear options={sourceColumns.map((item) => ({ value: item.name, label: `${item.name} · ${item.type}` }))} /></Form.Item></Col><Col span={8}><Form.Item name="defaultTimeGrain" label="默认时间粒度"><Select options={[{ value: 'none', label: '不分组' }, { value: 'day', label: '日' }, { value: 'month', label: '月' }, { value: 'year', label: '年' }]} /></Form.Item></Col><Col span={8}><Form.Item name="dimensionIds" label="默认维度"><Select mode="multiple" placeholder={selectedTableId ? '选择同表已发布维度' : '先选择来源表'} options={compatibleDimensions.map((item) => ({ value: item.id, label: `${item.dimensionCode} · ${item.dimensionName}` }))} /></Form.Item></Col></Row>
        <Form.Item name="calibreFormula" label="业务口径说明"><Input.TextArea rows={2} placeholder="用业务语言说明分子、分母、有效状态和数据范围；不可在此执行 SQL" /></Form.Item>
        <Form.Item name="description" label="使用说明"><Input.TextArea rows={2} maxLength={512} showCount /></Form.Item>
      </Form>
    </Modal>

    <Modal title={`指标试算 · ${previewing?.metricName || ''}`} open={Boolean(previewing)} onCancel={() => { setPreviewing(null); setPreview(null) }} footer={null} width="min(1050px, 94vw)">
      <Form form={previewForm} layout="inline" style={{ marginBottom: 16 }}><Form.Item name="dates" label="日期范围"><DatePicker.RangePicker /></Form.Item><Form.Item name="timeGrain" label="粒度"><Select style={{ width: 120 }} options={[{ value: 'none', label: '不分组' }, { value: 'day', label: '日' }, { value: 'month', label: '月' }, { value: 'year', label: '年' }]} /></Form.Item><Form.Item name="dimensions" label="维度"><Select mode="multiple" style={{ minWidth: 220 }} options={dimensions.filter((item) => item.metadataTableId === previewing?.sourceTableId).map((item) => ({ value: item.dimensionCode, label: item.dimensionName }))} /></Form.Item><Button type="primary" loading={querying} onClick={() => void executePreview()}>执行试算</Button></Form>
      {preview ? <><Typography.Paragraph type="secondary">口径：{preview.metricCode} v{preview.version} · {preview.unit || '无单位'}</Typography.Paragraph><Table size="small" rowKey="key" dataSource={resultRows(preview)} pagination={false} scroll={{ x: 'max-content', y: 420 }} columns={preview.columns.map((column) => ({ title: column, dataIndex: column, key: column, width: 160 }))} /><Divider /><Typography.Text code copyable>{preview.generatedSql}</Typography.Text></> : <Typography.Text type="secondary">选择日期和维度后执行试算。草稿也可以试算，但只有发布版本会向看板和智能问数开放。</Typography.Text>}
    </Modal>
    <Modal title={`版本记录 · ${versions?.title || ''}`} open={Boolean(versions)} onCancel={() => setVersions(null)} footer={null} width={720}><Table rowKey="id" size="small" dataSource={versions?.rows || []} pagination={false} columns={[{ title: '版本', dataIndex: 'versionNo', render: (value) => `v${value}` }, { title: '发布人', dataIndex: 'publishedBy' }, { title: '发布时间', dataIndex: 'publishedAt', render: formatDateTime }]} /></Modal>
  </>
}
