import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { DotChartOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createMetricDefinition,
  deleteMetricDefinition,
  listMetricDefinitions,
  updateMetricDefinition,
  type MetricDefinition,
  type MetricDefinitionInput
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const layerMeta: Record<string, { label: string; color: string }> = {
  atomic: { label: '原子指标', color: 'blue' },
  derived: { label: '派生指标', color: 'cyan' },
  composite: { label: '复合指标', color: 'purple' }
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' },
  published: { label: '已发布', tone: 'success' },
  deprecated: { label: '已废弃', tone: 'warning' }
}

export default function MetricManagePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<MetricDefinition[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<MetricDefinition | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<MetricDefinitionInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listMetricDefinitions())
    } catch {
      message.error('指标定义加载失败')
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
          item.metricName.includes(keyword) ||
          item.metricCode.toLowerCase().includes(keyword.toLowerCase()) ||
          (item.businessDomain ?? '').includes(keyword)
        const matchStatus = !statusFilter || item.status === statusFilter
        return matchKeyword && matchStatus
      }),
    [allData, keyword, statusFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      published: allData.filter((item) => item.status === 'published').length,
      domains: new Set(allData.map((item) => item.businessDomain).filter(Boolean)).size
    }),
    [allData]
  )

  const handleSubmit = async (values: MetricDefinitionInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateMetricDefinition(editing.id, values)
        message.success('指标已更新')
      } else {
        await createMetricDefinition(values)
        message.success('指标已创建')
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

  const handleDelete = async (record: MetricDefinition) => {
    try {
      await deleteMetricDefinition(record.id)
      message.success('指标已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<MetricDefinition> = [
    {
      title: '指标编码',
      dataIndex: 'metricCode',
      key: 'metricCode',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '指标名称', dataIndex: 'metricName', key: 'metricName', width: 160 },
    { title: '业务域', dataIndex: 'businessDomain', key: 'businessDomain', width: 110, render: (v?: string) => v || '—' },
    {
      title: '指标层级',
      dataIndex: 'layer',
      key: 'layer',
      width: 100,
      render: (v?: string) => {
        const meta = v ? layerMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    {
      title: '计算口径',
      dataIndex: 'calibreFormula',
      key: 'calibreFormula',
      ellipsis: true,
      render: (v?: string) => v || '—'
    },
    { title: '单位', dataIndex: 'unit', key: 'unit', width: 70, render: (v?: string) => v || '—' },
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 90, render: (v?: string) => v || '—' },
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
                  title: `删除指标“${record.metricName}”？`,
                  content: '删除后该指标口径将不再受平台管理，此操作不可恢复。',
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
        description="统一定义业务指标的计算口径、归属与版本，确保各消费场景口径一致。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增指标
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="指标总数" value={String(stats.total)} icon={<DotChartOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已发布" value={String(stats.published)} icon={<DotChartOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="覆盖业务域" value={String(stats.domains)} icon={<DotChartOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码/域</span>
            <Input placeholder="搜索指标名称、编码或业务域" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              allowClear
              placeholder="全部状态"
              value={statusFilter}
              onChange={setStatusFilter}
              className="filter-control-xl"
              options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="指标定义列表">
        <Table<MetricDefinition>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无指标定义' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑指标' : '新增指标'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ layer: 'atomic', status: 'draft', version: 1 }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="metricCode" label="指标编码" rules={[{ required: true, message: '请输入指标编码' }]}>
                <Input placeholder="如 metric_order_amt" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="metricName" label="指标名称" rules={[{ required: true, message: '请输入指标名称' }]}>
                <Input placeholder="如 订单成交金额" />
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
              <Form.Item name="layer" label="指标层级">
                <Select options={Object.entries(layerMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="calibreFormula" label="计算口径">
            <Input.TextArea rows={3} placeholder="如 SUM(pay_amount) WHERE pay_status = 'SUCCESS'" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="unit" label="单位">
                <Input placeholder="如 元、人次" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="负责人姓名或账号" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="status" label="状态">
                <Select options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="version" label="版本号">
                <InputNumber min={1} style={{ width: '100%' }} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} placeholder="业务含义与使用场景说明" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
