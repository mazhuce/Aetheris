import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ApiOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createApiAsset,
  deleteApiAsset,
  listApiAssets,
  updateApiAsset,
  type ApiAsset,
  type ApiAssetInput
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const methodColor: Record<string, string> = {
  GET: 'blue',
  POST: 'green',
  PUT: 'orange',
  DELETE: 'red',
  PATCH: 'purple'
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  registered: { label: '已登记', tone: 'neutral' },
  published: { label: '已发布', tone: 'success' },
  deprecated: { label: '已废弃', tone: 'warning' }
}

export default function ApiAssetPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<ApiAsset[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ApiAsset | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<ApiAssetInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listApiAssets())
    } catch {
      message.error('API 资产加载失败')
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
          item.assetName.includes(keyword) ||
          item.assetCode.toLowerCase().includes(keyword.toLowerCase()) ||
          item.apiPath.toLowerCase().includes(keyword.toLowerCase())
        const matchStatus = !statusFilter || item.status === statusFilter
        return matchKeyword && matchStatus
      }),
    [allData, keyword, statusFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      published: allData.filter((item) => item.status === 'published').length,
      categories: new Set(allData.map((item) => item.category).filter(Boolean)).size
    }),
    [allData]
  )

  const handleSubmit = async (values: ApiAssetInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateApiAsset(editing.id, values)
        message.success('API 资产已更新')
      } else {
        await createApiAsset(values)
        message.success('API 资产已登记')
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

  const handleDelete = async (record: ApiAsset) => {
    try {
      await deleteApiAsset(record.id)
      message.success('API 资产已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<ApiAsset> = [
    {
      title: '资产编码',
      dataIndex: 'assetCode',
      key: 'assetCode',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '资产名称', dataIndex: 'assetName', key: 'assetName', width: 170 },
    {
      title: '方法',
      dataIndex: 'httpMethod',
      key: 'httpMethod',
      width: 80,
      render: (v?: string) => (v ? <Tag color={methodColor[v.toUpperCase()] ?? 'default'}>{v.toUpperCase()}</Tag> : '—')
    },
    {
      title: '接口路径',
      dataIndex: 'apiPath',
      key: 'apiPath',
      width: 260,
      ellipsis: true,
      render: (v: string) => <Typography.Text copyable={{ text: v }}>{v}</Typography.Text>
    },
    { title: '分类', dataIndex: 'category', key: 'category', width: 110, render: (v?: string) => v || '—' },
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
    { title: '版本', dataIndex: 'version', key: 'version', width: 70, render: (v?: string) => v || 'v1' },
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
                  title: `删除 API 资产“${record.assetName}”？`,
                  content: '删除后该资产登记记录将不可恢复。',
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
        description="集中登记与展示可复用的数据 API 资产，统一检索与复用入口。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              登记API
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="API 总数" value={String(stats.total)} icon={<ApiOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已发布" value={String(stats.published)} icon={<ApiOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="业务分类" value={String(stats.categories)} icon={<ApiOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码/路径</span>
            <Input placeholder="搜索资产名称、编码或接口路径" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
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
      <DataSurface label="API 资产列表">
        <Table<ApiAsset>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无 API 资产' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑 API 资产' : '登记 API 资产'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ httpMethod: 'GET', status: 'registered', version: 'v1' }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="assetCode" label="资产编码" rules={[{ required: true, message: '请输入资产编码' }]}>
                <Input placeholder="如 api_order_detail" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="assetName" label="资产名称" rules={[{ required: true, message: '请输入资产名称' }]}>
                <Input placeholder="如 订单明细查询" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={6}>
              <Form.Item name="httpMethod" label="请求方法">
                <Select options={['GET', 'POST', 'PUT', 'DELETE', 'PATCH'].map((v) => ({ value: v, label: v }))} />
              </Form.Item>
            </Col>
            <Col span={18}>
              <Form.Item name="apiPath" label="接口路径" rules={[{ required: true, message: '请输入接口路径' }]}>
                <Input placeholder="如 /api/serving/orders/detail" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="category" label="业务分类">
                <Input placeholder="如 交易服务" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="负责人姓名或账号" />
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
              <Form.Item name="version" label="版本">
                <Input placeholder="如 v1" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} placeholder="接口能力与适用场景说明" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
