import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { PartitionOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createLogicalModel,
  deleteLogicalModel,
  listLogicalModels,
  updateLogicalModel,
  type LogicalModel,
  type LogicalModelInput
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const modelTypeMeta: Record<string, { label: string; color: string }> = {
  conceptual: { label: '概念模型', color: 'default' },
  relational: { label: '关系模型', color: 'blue' },
  dimensional: { label: '维度模型', color: 'gold' }
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' },
  published: { label: '已发布', tone: 'success' },
  deprecated: { label: '已废弃', tone: 'warning' }
}

/** 从 entitiesJson 提取实体数量，用于列表展示与统计。 */
function countEntities(model: LogicalModel): number {
  const raw = model.entitiesJson
  if (Array.isArray(raw)) return raw.length
  if (raw && typeof raw === 'object' && Array.isArray((raw as { entities?: unknown[] }).entities)) {
    return (raw as { entities: unknown[] }).entities.length
  }
  return 0
}

export default function LogicalModelPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<LogicalModel[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [typeFilter, setTypeFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<LogicalModel | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<LogicalModelInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listLogicalModels())
    } catch {
      message.error('逻辑模型加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (open) {
      if (editing) form.setFieldsValue(editing)
      else form.resetFields()
    }
  }, [open, editing, form])

  const filtered = useMemo(
    () =>
      allData.filter((item) => {
        const matchKeyword =
          !keyword ||
          item.modelName.includes(keyword) ||
          item.modelCode.toLowerCase().includes(keyword.toLowerCase()) ||
          (item.businessDomain ?? '').includes(keyword)
        const matchType = !typeFilter || item.modelType === typeFilter
        return matchKeyword && matchType
      }),
    [allData, keyword, typeFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      published: allData.filter((item) => item.status === 'published').length,
      entities: allData.reduce((sum, item) => sum + countEntities(item), 0)
    }),
    [allData]
  )

  const handleSubmit = async (values: LogicalModelInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateLogicalModel(editing.id, values)
        message.success('模型已更新')
      } else {
        await createLogicalModel(values)
        message.success('模型已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (record: LogicalModel) => {
    try {
      await deleteLogicalModel(record.id)
      message.success('模型已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<LogicalModel> = [
    {
      title: '模型编码',
      dataIndex: 'modelCode',
      key: 'modelCode',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '模型名称', dataIndex: 'modelName', key: 'modelName', width: 170 },
    { title: '业务域', dataIndex: 'businessDomain', key: 'businessDomain', width: 100, render: (v?: string) => v || '—' },
    {
      title: '模型类型',
      dataIndex: 'modelType',
      key: 'modelType',
      width: 100,
      render: (v?: string) => {
        const meta = v ? modelTypeMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    { title: '实体数', key: 'entities', width: 80, render: (_, record) => countEntities(record) || '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v?: string) => {
        const meta = v ? statusMeta[v] : undefined
        return meta ? <StatusBadge tone={meta.tone}>{meta.label}</StatusBadge> : '—'
      }
    },
    { title: '版本', dataIndex: 'version', key: 'version', width: 60, render: (v?: number) => `v${v ?? 1}` },
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 90, render: (v?: string) => v || '—' },
    { title: '说明', dataIndex: 'description', key: 'description', ellipsis: true, render: (v?: string) => v || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (v?: string) => formatDateTime(v) }
  ]

  if (canManage) {
    columns.push({
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      render: (_, record) => (
        <RowActions
          items={[
            { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
            {
              key: 'delete',
              label: '删除',
              danger: true,
              onClick: () =>
                modal.confirm({
                  title: `删除模型“${record.modelName}”？`,
                  content: '删除后该逻辑模型设计记录将不可恢复。',
                  okText: '确认删除',
                  okButtonProps: { danger: true },
                  onOk: () => handleDelete(record)
                })
            }
          ]}
        />
      )
    })
  }

  return (
    <>
      <PageHeader
        description="面向业务的概念与逻辑模型设计，沉淀实体关系与业务口径。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增模型
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="模型总数" value={String(stats.total)} icon={<PartitionOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已发布" value={String(stats.published)} icon={<PartitionOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="实体总数" value={String(stats.entities)} icon={<PartitionOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码/域</span>
            <Input placeholder="搜索模型名称、编码或业务域" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
          </div>
          <div className="filter-item">
            <span>模型类型</span>
            <Select
              allowClear
              placeholder="全部类型"
              value={typeFilter}
              onChange={setTypeFilter}
              className="filter-control-xl"
              options={Object.entries(modelTypeMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="逻辑模型列表">
        <Table<LogicalModel>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无逻辑模型' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑模型' : '新增模型'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ modelType: 'relational', status: 'draft', version: 1 }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="modelCode" label="模型编码" rules={[{ required: true, message: '请输入模型编码' }]}>
                <Input placeholder="如 lm_trade_order" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="modelName" label="模型名称" rules={[{ required: true, message: '请输入模型名称' }]}>
                <Input placeholder="如 交易订单模型" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="businessDomain" label="业务域">
                <Input placeholder="如 交易域" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="modelType" label="模型类型">
                <Select options={Object.entries(modelTypeMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="status" label="状态">
                <Select options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="version" label="版本号">
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="负责人姓名或账号" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="模型设计目标、覆盖业务过程与关键实体说明" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
