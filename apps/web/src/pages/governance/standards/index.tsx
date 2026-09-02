import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AuditOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createDataStandard,
  deleteDataStandard,
  listDataStandards,
  updateDataStandard,
  type DataStandard,
  type DataStandardInput
} from '@/api/governance'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const typeMeta: Record<string, { label: string; color: string }> = {
  naming: { label: '命名标准', color: 'blue' },
  encoding: { label: '编码标准', color: 'cyan' },
  value: { label: '取值标准', color: 'purple' },
  format: { label: '格式标准', color: 'gold' }
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' | 'warning' }> = {
  draft: { label: '草稿', tone: 'neutral' },
  published: { label: '已发布', tone: 'success' },
  deprecated: { label: '已废弃', tone: 'warning' }
}

export default function DataStandardPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<DataStandard[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<string>()
  const [typeFilter, setTypeFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataStandard | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<DataStandardInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listDataStandards())
    } catch {
      message.error('数据标准加载失败')
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
          item.standardName.includes(keyword) ||
          item.standardCode.toLowerCase().includes(keyword.toLowerCase()) ||
          (item.definition ?? '').includes(keyword)
        const matchStatus = !statusFilter || item.status === statusFilter
        const matchType = !typeFilter || item.standardType === typeFilter
        return matchKeyword && matchStatus && matchType
      }),
    [allData, keyword, statusFilter, typeFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      published: allData.filter((item) => item.status === 'published').length,
      types: new Set(allData.map((item) => item.standardType).filter(Boolean)).size
    }),
    [allData]
  )

  const handleSubmit = async (values: DataStandardInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateDataStandard(editing.id, values)
        message.success('标准已更新')
      } else {
        await createDataStandard(values)
        message.success('标准已创建')
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

  const handleDelete = async (record: DataStandard) => {
    try {
      await deleteDataStandard(record.id)
      message.success('标准已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<DataStandard> = [
    {
      title: '标准编码',
      dataIndex: 'standardCode',
      key: 'standardCode',
      width: 140,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '标准名称', dataIndex: 'standardName', key: 'standardName', width: 180 },
    {
      title: '标准类型',
      dataIndex: 'standardType',
      key: 'standardType',
      width: 100,
      render: (v?: string) => {
        const meta = v ? typeMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    { title: '适用范围', dataIndex: 'applyScope', key: 'applyScope', width: 110, render: (v?: string) => v || '—' },
    {
      title: '标准定义',
      dataIndex: 'definition',
      key: 'definition',
      ellipsis: true,
      render: (v?: string) => v || '—'
    },
    {
      title: '规则表达式',
      dataIndex: 'ruleExpression',
      key: 'ruleExpression',
      width: 220,
      ellipsis: true,
      render: (v?: string) => (v ? <Typography.Text code>{v}</Typography.Text> : '—')
    },
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
    { title: '负责人', dataIndex: 'owner', key: 'owner', width: 90, render: (v?: string) => v || '—' },
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
                  title: `删除标准“${record.standardName}”？`,
                  content: '删除后该数据标准将不再受平台维护，此操作不可恢复。',
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
        description="维护字段命名、编码与取值标准，统一平台数据规范，从源头保障数据一致性。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增标准
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="标准总数" value={String(stats.total)} icon={<AuditOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已发布" value={String(stats.published)} icon={<AuditOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="覆盖类型" value={String(stats.types)} icon={<AuditOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/编码/定义</span>
            <Input placeholder="搜索标准名称、编码或定义" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
          </div>
          <div className="filter-item">
            <span>标准类型</span>
            <Select
              allowClear
              placeholder="全部类型"
              value={typeFilter}
              onChange={setTypeFilter}
              className="filter-control-xl"
              options={Object.entries(typeMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
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
      <DataSurface label="数据标准列表">
        <Table<DataStandard>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1300 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无数据标准' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑标准' : '新增标准'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={640}
      >
        <Form form={form} layout="vertical" initialValues={{ standardType: 'naming', status: 'draft', version: 1 }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="standardCode" label="标准编码" rules={[{ required: true, message: '请输入标准编码' }]}>
                <Input placeholder="如 STD_FIELD_NAMING" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="standardName" label="标准名称" rules={[{ required: true, message: '请输入标准名称' }]}>
                <Input placeholder="如 字段命名规范" />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={8}>
              <Form.Item name="standardType" label="标准类型">
                <Select options={Object.entries(typeMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="applyScope" label="适用范围">
                <Input placeholder="如 全平台" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="owner" label="负责人">
                <Input placeholder="负责人姓名或账号" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="definition" label="标准定义">
            <Input.TextArea rows={2} placeholder="标准的业务定义与适用说明" />
          </Form.Item>
          <Form.Item name="ruleExpression" label="规则表达式">
            <Input.TextArea rows={2} placeholder="如 ^[a-z][a-z0-9_(_)]*$" />
          </Form.Item>
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
              <Form.Item name="referenceDoc" label="参考文档">
                <Input placeholder="文档链接" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </>
  )
}
