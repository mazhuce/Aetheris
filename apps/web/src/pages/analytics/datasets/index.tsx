import { useEffect, useMemo, useState } from 'react'
import { DeleteOutlined, EditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  Button,
  Form,
  Input,
  InputNumber,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag,
  Typography,
  message,
  type TableColumnsType
} from 'antd'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { listDataSources, listPublishedMetrics, listSemanticDimensions, type DataSource, type MetricDefinition, type SemanticDimension } from '@/api/metadata'
import {
  createAnalyticsDataset,
  deleteAnalyticsDataset,
  listAnalyticsDatasets,
  publishAnalyticsDataset,
  queryAnalyticsDataset,
  updateAnalyticsDataset,
  type AnalyticsDataset,
  type AnalyticsQueryResult,
  type DatasetInput
} from '@/api/analytics'
import { getCurrentRole } from '@/utils/access'

function resultRows(result: AnalyticsQueryResult) {
  return result.rows.map((row, rowIndex) => {
    const record: Record<string, unknown> = { key: rowIndex }
    result.columns.forEach((column, index) => { record[column] = row[index] })
    return record
  })
}

export default function AnalyticsDatasetPage() {
  const [datasets, setDatasets] = useState<AnalyticsDataset[]>([])
  const [sources, setSources] = useState<DataSource[]>([])
  const [metrics, setMetrics] = useState<MetricDefinition[]>([])
  const [dimensions, setDimensions] = useState<SemanticDimension[]>([])
  const [loading, setLoading] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<AnalyticsDataset | null>(null)
  const [preview, setPreview] = useState<{ dataset: AnalyticsDataset; result: AnalyticsQueryResult } | null>(null)
  const [querying, setQuerying] = useState<number | null>(null)
  const [form] = Form.useForm<DatasetInput>()
  const sourceType = Form.useWatch('sourceType', form) || 'sql'
  const metricCode = Form.useWatch('metricCode', form)
  const isAdmin = getCurrentRole() === 'admin'

  const reload = async () => {
    setLoading(true)
    try {
      const [nextDatasets, nextSources, nextMetrics, nextDimensions] = await Promise.all([
        listAnalyticsDatasets(), listDataSources(), listPublishedMetrics(), listSemanticDimensions(undefined, 'published')
      ])
      setDatasets(nextDatasets)
      setSources(nextSources)
      setMetrics(nextMetrics)
      setDimensions(nextDimensions)
    } catch {
      message.error('数据集加载失败')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { void reload() }, [])

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    form.setFieldsValue({ refreshSeconds: 30, sourceType: 'sql', metricQuery: { timeGrain: 'none', dimensions: [], limit: 100 } })
    setEditorOpen(true)
  }

  const openEdit = (dataset: AnalyticsDataset) => {
    setEditing(dataset)
    form.setFieldsValue({
      name: dataset.name,
      description: dataset.description,
      sourceType: dataset.sourceType || 'sql',
      datasourceId: dataset.datasourceId,
      datasourceName: dataset.datasourceName,
      querySql: dataset.querySql,
      metricCode: dataset.metricCode,
      metricQuery: dataset.metricQuery,
      refreshSeconds: dataset.refreshSeconds
    })
    setEditorOpen(true)
  }

  const save = async () => {
    const values = await form.validateFields()
    const source = sources.find((item) => item.id === values.datasourceId)
    const input = {
      ...values,
      datasourceId: values.sourceType === 'sql' ? values.datasourceId : undefined,
      datasourceName: values.sourceType === 'sql' ? source?.name : undefined,
      querySql: values.sourceType === 'sql' ? values.querySql : undefined,
      metricCode: values.sourceType === 'metric' ? values.metricCode : undefined,
      metricQuery: values.sourceType === 'metric' ? values.metricQuery : undefined
    }
    try {
      const saved = editing
        ? await updateAnalyticsDataset(editing.id, input)
        : await createAnalyticsDataset(input)
      setEditorOpen(false)
      message.success(editing ? '数据集已保存，请发布后供查询和看板使用' : '数据集已创建，请发布后供查询和看板使用')
      await reload()
      if (saved.status === 'published') await runPreview(saved)
    } catch (error: any) {
      message.error(error.response?.data?.message || '数据集保存失败')
    }
  }

  const runPreview = async (dataset: AnalyticsDataset) => {
    setQuerying(dataset.id)
    try {
      const result = await queryAnalyticsDataset(dataset.id, true)
      setPreview({ dataset, result })
      await reload()
    } catch (error: any) {
      message.error(error.response?.data?.message || 'SQL 查询失败')
    } finally {
      setQuerying(null)
    }
  }

  const publish = async (dataset: AnalyticsDataset) => {
    try {
      await publishAnalyticsDataset(dataset.id)
      message.success('数据集已发布，可用于查询和看板')
      await reload()
    } catch (error: any) {
      message.error(error.response?.data?.message || '数据集发布失败')
    }
  }

  const columns: TableColumnsType<AnalyticsDataset> = [
    {
      title: '数据集',
      dataIndex: 'name',
      width: 250,
      render: (value, record) => (
        <div className="dataset-name-cell"><strong>{value}</strong><span>{record.description || '暂无描述'}</span></div>
      )
    },
    { title: '来源类型', dataIndex: 'sourceType', width: 100, render: (value) => <Tag color={value === 'metric' ? 'purple' : 'default'}>{value === 'metric' ? '语义指标' : '只读 SQL'}</Tag> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value: AnalyticsDataset['status']) => <Tag color={value === 'published' ? 'green' : value === 'draft' ? 'gold' : 'default'}>{value === 'published' ? '已发布' : value === 'draft' ? '草稿' : '已废弃'}</Tag> },
    { title: '数据来源', width: 190, render: (_, row) => row.sourceType === 'metric' ? <Typography.Text code>{row.metricCode}</Typography.Text> : row.datasourceName || '—' },
    {
      title: '字段',
      width: 90,
      render: (_, record) => <Tag>{record.schema?.columns?.length || 0} 个</Tag>
    },
    { title: '刷新', dataIndex: 'refreshSeconds', width: 100, render: (value) => `${value} 秒` },
    { title: '创建人', dataIndex: 'createdBy', width: 120 },
    {
      title: '操作',
      key: 'actions',
      width: 210,
      fixed: 'right',
      render: (_, record) => (
        <Space size={4}>
          <Button type="text" disabled={record.status !== 'published'} loading={querying === record.id} onClick={() => void runPreview(record)}>预览</Button>
          {isAdmin && record.status === 'draft' && <Button type="text" onClick={() => void publish(record)}>发布</Button>}
          {isAdmin && <Button type="text" icon={<EditOutlined />} onClick={() => openEdit(record)}>编辑</Button>}
          {isAdmin && (
            <Popconfirm title="删除这个数据集？" description="已绑定的看板组件会失去数据。" onConfirm={async () => {
              await deleteAnalyticsDataset(record.id)
              message.success('数据集已删除')
              await reload()
            }}>
              <Button type="text" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </Space>
      )
    }
  ]

  const previewColumns = useMemo(() => preview?.result.columns.map((column) => ({
    title: column,
    dataIndex: column,
    key: column,
    width: 160,
    ellipsis: true
  })) || [], [preview])

  return (
    <div className="page-shell analytics-dataset-page">
      <PageHeader
        description="将中台加工后的表与指标封装为稳定、可复用的看板数据接口。"
        extra={(
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void reload()}>刷新</Button>
            {isAdmin && <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>新建数据集</Button>}
          </Space>
        )}
      />
      <DataSurface title="数据集目录" description="图表只绑定数据集，不直接暴露数据源连接和凭据。">
        <Table<AnalyticsDataset>
          rowKey="id"
          loading={loading}
          columns={columns}
          dataSource={datasets}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showSizeChanger: false }}
        />
      </DataSurface>

      <Modal
        title={editing ? '编辑数据集' : '新建数据集'}
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        onOk={() => void save()}
        okText="保存并校验"
        width={720}
      >
        <Form form={form} layout="vertical" className="dataset-form">
          <div className="dataset-form-grid">
            <Form.Item name="name" label="数据集名称" rules={[{ required: true, message: '请输入数据集名称' }]}>
              <Input placeholder="例如：每日销售趋势" maxLength={128} />
            </Form.Item>
            <Form.Item name="sourceType" label="数据集来源" rules={[{ required: true }]}>
              <Select options={[{ value: 'metric', label: '企业语义指标（推荐）' }, { value: 'sql', label: '受控只读 SQL' }]} />
            </Form.Item>
            {sourceType === 'sql' && <Form.Item name="datasourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择 Aetheris 已管理的数据源"
                options={sources.map((source) => ({ value: source.id, label: source.name }))}
              />
            </Form.Item>}
          </div>
          <Form.Item name="description" label="业务说明">
            <Input placeholder="说明指标口径、数据范围和适用场景" maxLength={512} />
          </Form.Item>
          {sourceType === 'sql' ? <Form.Item
            name="querySql"
            label="只读 SQL"
            extra="仅允许 SELECT / SHOW / DESCRIBE / EXPLAIN；正式看板建议查询 ADS 表或物化视图。"
            rules={[{ required: true, message: '请输入只读 SQL' }]}
          >
            <Input.TextArea className="dataset-sql-input" rows={9} placeholder="SELECT business_date, sales_amount FROM ads_sales_daily ORDER BY business_date" />
          </Form.Item> : <>
            <Form.Item name="metricCode" label="已发布指标" rules={[{ required: true, message: '请选择已发布指标' }]}>
              <Select showSearch optionFilterProp="label" placeholder="选择企业统一指标" options={metrics.map((item) => ({ value: item.metricCode, label: `${item.metricCode} · ${item.metricName} · v${item.version}` }))} />
            </Form.Item>
            <div className="dataset-form-grid">
              <Form.Item name={['metricQuery', 'timeGrain']} label="时间粒度"><Select options={[{ value: 'none', label: '不分组' }, { value: 'day', label: '日' }, { value: 'month', label: '月' }, { value: 'year', label: '年' }]} /></Form.Item>
              <Form.Item name={['metricQuery', 'dimensions']} label="分析维度">
                <Select mode="multiple" placeholder="选择与指标同表的已发布维度" options={dimensions.filter((dimension) => dimension.metadataTableId === metrics.find((metric) => metric.metricCode === metricCode)?.sourceTableId).map((item) => ({ value: item.dimensionCode, label: `${item.dimensionCode} · ${item.dimensionName}` }))} />
              </Form.Item>
            </div>
            <Form.Item name={['metricQuery', 'limit']} hidden><InputNumber /></Form.Item>
          </>}
          <Form.Item name="refreshSeconds" label="默认刷新周期（秒）" rules={[{ required: true }]}>
            <InputNumber min={5} max={3600} step={5} style={{ width: 180 }} />
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`数据预览 · ${preview?.dataset.name || ''}`}
        open={Boolean(preview)}
        onCancel={() => setPreview(null)}
        footer={null}
        width="min(1100px, 92vw)"
      >
        {preview && (
          <Table
            size="small"
            columns={previewColumns}
            dataSource={resultRows(preview.result)}
            scroll={{ x: 'max-content', y: 460 }}
            pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 行` }}
          />
        )}
      </Modal>
    </div>
  )
}
