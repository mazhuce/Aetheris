import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, Modal, Row, Select, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { BellOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createSubscription,
  deleteSubscription,
  listSubscriptions,
  updateSubscription,
  type DataSubscription,
  type SubscriptionInput
} from '@/api/serving'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const targetMeta: Record<string, { label: string; color: string }> = {
  table: { label: '数据表', color: 'blue' },
  api: { label: '数据API', color: 'geekblue' },
  product: { label: '数据产品', color: 'purple' },
  dataset: { label: '数据集', color: 'cyan' }
}

const deliverMeta: Record<string, { label: string; color: string }> = {
  api: { label: 'API 拉取', color: 'blue' },
  file: { label: '文件下发', color: 'gold' },
  stream: { label: '流式推送', color: 'green' },
  email: { label: '邮件推送', color: 'default' }
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  active: { label: '生效中', tone: 'success' },
  paused: { label: '已暂停', tone: 'warning' },
  cancelled: { label: '已取消', tone: 'neutral' }
}

export default function SubscriptionPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<DataSubscription[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataSubscription | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<SubscriptionInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listSubscriptions())
    } catch {
      message.error('订阅列表加载失败')
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

  const filtered = useMemo(() => allData.filter((item) => !statusFilter || item.status === statusFilter), [allData, statusFilter])

  const stats = useMemo(
    () => ({
      total: allData.length,
      active: allData.filter((item) => item.status === 'active').length,
      subscribers: new Set(allData.map((item) => item.subscriber)).size
    }),
    [allData]
  )

  const handleSubmit = async (values: SubscriptionInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateSubscription(editing.id, values)
        message.success('订阅已更新')
      } else {
        await createSubscription(values)
        message.success('订阅已创建')
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

  const handleDelete = async (record: DataSubscription) => {
    try {
      await deleteSubscription(record.id)
      message.success('订阅已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<DataSubscription> = [
    { title: '订阅名称', dataIndex: 'subscriptionName', key: 'subscriptionName', width: 180, ellipsis: true },
    { title: '订阅方', dataIndex: 'subscriber', key: 'subscriber', width: 130 },
    {
      title: '订阅对象',
      dataIndex: 'targetName',
      key: 'targetName',
      width: 220,
      ellipsis: true
    },
    {
      title: '对象类型',
      dataIndex: 'targetType',
      key: 'targetType',
      width: 100,
      render: (v?: string) => {
        const meta = v ? targetMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    {
      title: '下发方式',
      dataIndex: 'deliverType',
      key: 'deliverType',
      width: 100,
      render: (v?: string) => {
        const meta = v ? deliverMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    { title: '推送频率', dataIndex: 'frequency', key: 'frequency', width: 130, render: (v?: string) => v || '—' },
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
    { title: '创建人', dataIndex: 'createdBy', key: 'createdBy', width: 100, render: (v?: string) => v || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v?: string) => formatDateTime(v) }
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
                  title: `删除订阅“${record.subscriptionName}”？`,
                  content: '删除后该订阅的下发配置将一并移除，此操作不可恢复。',
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
        description="管理数据订阅需求与下发配置，打通数据资产到业务消费方的最后一公里。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增订阅
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="订阅总数" value={String(stats.total)} icon={<BellOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="生效中" value={String(stats.active)} icon={<BellOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="订阅方" value={String(stats.subscribers)} icon={<BellOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
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
      <DataSurface label="数据订阅列表">
        <Table<DataSubscription>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1300 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无数据订阅' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑订阅' : '新增订阅'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ targetType: 'table', deliverType: 'api', status: 'active' }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="subscriptionName" label="订阅名称" rules={[{ required: true, message: '请输入订阅名称' }]}>
                <Input placeholder="如 增长团队-用户标签订阅" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="subscriber" label="订阅方" rules={[{ required: true, message: '请输入订阅方' }]}>
                <Input placeholder="如 增长团队" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="targetType" label="对象类型">
                <Select options={Object.entries(targetMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
            <Col span={16}>
              <Form.Item name="targetName" label="订阅对象" rules={[{ required: true, message: '请输入订阅对象' }]}>
                <Input placeholder="如 ads_user_profile / api_user_tags" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="deliverType" label="下发方式">
                <Select options={Object.entries(deliverMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="frequency" label="推送频率">
                <Input placeholder="如 每日 08:00" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="status" label="状态">
                <Select options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </>
  )
}
