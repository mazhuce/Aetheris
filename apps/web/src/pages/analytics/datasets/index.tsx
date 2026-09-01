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
  message,
  type TableColumnsType
} from 'antd'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { listDataSources, type DataSource } from '@/api/metadata'
import {
  createAnalyticsDataset,
  deleteAnalyticsDataset,
  listAnalyticsDatasets,
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
  const [loading, setLoading] = useState(false)
  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<AnalyticsDataset | null>(null)
  const [preview, setPreview] = useState<{ dataset: AnalyticsDataset; result: AnalyticsQueryResult } | null>(null)
  const [querying, setQuerying] = useState<number | null>(null)
  const [form] = Form.useForm<DatasetInput>()
  const isAdmin = getCurrentRole() === 'admin'

  const reload = async () => {
    setLoading(true)
    try {
      const [nextDatasets, nextSources] = await Promise.all([listAnalyticsDatasets(), listDataSources()])
      setDatasets(nextDatasets)
      setSources(nextSources)
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
    form.setFieldsValue({ refreshSeconds: 30 })
    setEditorOpen(true)
  }

  const openEdit = (dataset: AnalyticsDataset) => {
    setEditing(dataset)
    form.setFieldsValue({
      name: dataset.name,
      description: dataset.description,
      datasourceId: dataset.datasourceId,
      datasourceName: dataset.datasourceName,
      querySql: dataset.querySql,
      refreshSeconds: dataset.refreshSeconds
    })
    setEditorOpen(true)
  }

  const save = async () => {
    const values = await form.validateFields()
    const source = sources.find((item) => item.id === values.datasourceId)
    const input = { ...values, datasourceName: source?.name }
    try {
      const saved = editing
        ? await updateAnalyticsDataset(editing.id, input)
        : await createAnalyticsDataset(input)
      setEditorOpen(false)
      message.success(editing ? '数据集已更新' : '数据集已创建')
      await reload()
      await runPreview(saved)
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

  const columns: TableColumnsType<AnalyticsDataset> = [
    {
      title: '数据集',
      dataIndex: 'name',
      width: 250,
      render: (value, record) => (
        <div className="dataset-name-cell"><strong>{value}</strong><span>{record.description || '暂无描述'}</span></div>
      )
    },
    { title: '数据源', dataIndex: 'datasourceName', width: 170, render: (value) => value || '—' },
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
          <Button type="text" loading={querying === record.id} onClick={() => void runPreview(record)}>预览</Button>
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
            <Form.Item name="datasourceId" label="数据源" rules={[{ required: true, message: '请选择数据源' }]}>
              <Select
                showSearch
                optionFilterProp="label"
                placeholder="选择 Aetheris 已管理的数据源"
                options={sources.map((source) => ({ value: source.id, label: source.name }))}
              />
            </Form.Item>
          </div>
          <Form.Item name="description" label="业务说明">
            <Input placeholder="说明指标口径、数据范围和适用场景" maxLength={512} />
          </Form.Item>
          <Form.Item
            name="querySql"
            label="只读 SQL"
            extra="仅允许 SELECT / SHOW / DESCRIBE / EXPLAIN；正式看板建议查询 ADS 表或物化视图。"
            rules={[{ required: true, message: '请输入只读 SQL' }]}
          >
            <Input.TextArea className="dataset-sql-input" rows={9} placeholder="SELECT business_date, sales_amount FROM ads_sales_daily ORDER BY business_date" />
          </Form.Item>
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
            pagination={false}
          />
        )}
      </Modal>
    </div>
  )
}
