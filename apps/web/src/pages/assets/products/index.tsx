import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AppstoreOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createDataProduct,
  deleteDataProduct,
  listDataProducts,
  updateDataProduct,
  type DataProduct,
  type DataProductInput
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const lifecycleMeta: Record<string, { label: string; tone: 'neutral' | 'success' | 'warning' | 'danger' }> = {
  planning: { label: '规划中', tone: 'neutral' },
  developing: { label: '开发中', tone: 'warning' },
  released: { label: '已上线', tone: 'success' },
  offline: { label: '已下线', tone: 'danger' }
}

export default function DataProductPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<DataProduct[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [lifecycleFilter, setLifecycleFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataProduct | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<DataProductInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listDataProducts())
    } catch {
      message.error('数据产品加载失败')
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
          item.productName.includes(keyword) ||
          item.productCode.toLowerCase().includes(keyword.toLowerCase()) ||
          (item.category ?? '').includes(keyword)
        const matchLifecycle = !lifecycleFilter || item.lifecycle === lifecycleFilter
        return matchKeyword && matchLifecycle
      }),
    [allData, keyword, lifecycleFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      released: allData.filter((item) => item.lifecycle === 'released').length,
      developing: allData.filter((item) => item.lifecycle === 'developing').length
    }),
    [allData]
  )

  const handleSubmit = async (values: DataProductInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateDataProduct(editing.id, values)
        message.success('数据产品已更新')
      } else {
        await createDataProduct(values)
        message.success('数据产品已创建')
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

  const handleDelete = async (record: DataProduct) => {
    try {
      await deleteDataProduct(record.id)
      message.success('数据产品已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<DataProduct> = [
    {
      title: '产品编码',
      dataIndex: 'productCode',
      key: 'productCode',
      width: 150,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '产品名称', dataIndex: 'productName', key: 'productName', width: 170 },
    { title: '分类', dataIndex: 'category', key: 'category', width: 110, render: (v?: string) => v || '—' },
    {
      title: '生命周期',
      dataIndex: 'lifecycle',
      key: 'lifecycle',
      width: 100,
      render: (v?: string) => {
        const meta = v ? lifecycleMeta[v] : undefined
        return meta ? <StatusBadge tone={meta.tone}>{meta.label}</StatusBadge> : '—'
      }
    },
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 90, render: (v?: string) => v || '—' },
    { title: '消费方', dataIndex: 'consumers', key: 'consumers', width: 160, ellipsis: true, render: (v?: string) => v || '—' },
    { title: '服务等级', dataIndex: 'sla', key: 'sla', width: 150, ellipsis: true, render: (v?: string) => v || '—' },
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
                  title: `删除数据产品“${record.productName}”？`,
                  content: '删除后该产品记录将不可恢复，已交付内容不受影响。',
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
        description="管理面向业务交付的数据产品及其生命周期，从规划、开发到上线运营全程可追溯。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增产品
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="产品总数" value={String(stats.total)} icon={<AppstoreOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已上线" value={String(stats.released)} icon={<AppstoreOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="开发中" value={String(stats.developing)} icon={<AppstoreOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码/分类</span>
            <Input placeholder="搜索产品名称、编码或分类" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
          </div>
          <div className="filter-item">
            <span>生命周期</span>
            <Select
              allowClear
              placeholder="全部阶段"
              value={lifecycleFilter}
              onChange={setLifecycleFilter}
              className="filter-control-xl"
              options={Object.entries(lifecycleMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="数据产品列表">
        <Table<DataProduct>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1300 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无数据产品' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑数据产品' : '新增数据产品'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ lifecycle: 'planning' }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="productCode" label="产品编码" rules={[{ required: true, message: '请输入产品编码' }]}>
                <Input placeholder="如 dp_user_profile" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="productName" label="产品名称" rules={[{ required: true, message: '请输入产品名称' }]}>
                <Input placeholder="如 用户画像标签包" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="category" label="产品分类">
                <Input placeholder="如 营销服务" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="lifecycle" label="生命周期">
                <Select options={Object.entries(lifecycleMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="负责人姓名或账号" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="consumers" label="消费方">
                <Input placeholder="如 增长团队、CRM" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="sla" label="服务等级">
            <Input placeholder="如 每日 08:00 前完成更新" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={3} placeholder="产品定位、覆盖范围与交付形式说明" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
