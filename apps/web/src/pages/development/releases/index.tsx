import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, InputNumber, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CloudUploadOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  approveRelease,
  createRelease,
  deleteRelease,
  listReleases,
  publishRelease,
  rejectRelease,
  rollbackRelease,
  updateRelease,
  type ReleaseRecord,
  type ReleaseRecordInput,
  type ReleaseStatus
} from '@/api/workflow'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'
import { listRules, type QualityRule } from '@/api/quality'

const typeMeta: Record<string, { label: string; color: string }> = {
  table: { label: '数据表', color: 'blue' },
  task: { label: '加工任务', color: 'cyan' },
  dashboard: { label: '数据看板', color: 'purple' },
  api: { label: '数据API', color: 'geekblue' },
  model: { label: '数据模型', color: 'gold' }
}

const statusMeta: Record<ReleaseStatus, { label: string; tone: 'neutral' | 'success' | 'warning' | 'danger' | 'info' }> = {
  pending: { label: '待审批', tone: 'warning' },
  approved: { label: '已通过', tone: 'info' },
  rejected: { label: '已拒绝', tone: 'danger' },
  released: { label: '已上线', tone: 'success' },
  rolled_back: { label: '已回滚', tone: 'neutral' }
}

export default function ReleasePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<ReleaseRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>()
  const [typeFilter, setTypeFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ReleaseRecord | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<ReleaseRecordInput>()
  const [qualityRules, setQualityRules] = useState<QualityRule[]>([])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listReleases())
    } catch {
      message.error('发布记录加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
    void listRules().then((rules) => setQualityRules(rules.filter((rule) => rule.status === 'enabled'))).catch(() => undefined)
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
        const matchStatus = !statusFilter || item.status === statusFilter
        const matchType = !typeFilter || item.releaseType === typeFilter
        return matchStatus && matchType
      }),
    [allData, statusFilter, typeFilter]
  )

  const stats = useMemo(
    () => ({
      total: allData.length,
      released: allData.filter((item) => item.status === 'released').length,
      pending: allData.filter((item) => item.status === 'pending').length
    }),
    [allData]
  )

  const handleSubmit = async (values: ReleaseRecordInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateRelease(editing.id, values)
        message.success('发布单已更新')
      } else {
        await createRelease(values)
        message.success('发布单已创建')
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

  const handleDelete = async (record: ReleaseRecord) => {
    try {
      await deleteRelease(record.id)
      message.success('发布单已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  /** 状态动作统一入口：失败时展示后端返回的流转校验信息。 */
  const runAction = async (
    action: () => Promise<ReleaseRecord>,
    successText: string,
    confirm?: { title: string; content: string }
  ) => {
    const execute = async () => {
      try {
        await action()
        message.success(successText)
        await refresh()
      } catch (err) {
        const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
        message.error(detail || '操作失败')
      }
    }
    if (confirm) {
      modal.confirm({ ...confirm, onOk: execute })
    } else {
      await execute()
    }
  }

  /** 按当前状态推导可用动作，与后端状态机保持一致：pending→approved/rejected→released→rolled_back。 */
  const actionsFor = (record: ReleaseRecord): { key: string; label: string; danger?: boolean; onClick: () => void }[] => {
    const items: { key: string; label: string; danger?: boolean; onClick: () => void }[] = []
    if (record.status === 'pending') {
      items.push({ key: 'approve', label: '通过', onClick: () => void runAction(() => approveRelease(record.id), '已通过审批') })
      items.push({
        key: 'reject',
        label: '拒绝',
        danger: true,
        onClick: () =>
          void runAction(() => rejectRelease(record.id), '已拒绝', {
            title: `拒绝发布单“${record.title}”？`,
            content: '拒绝后该发布单将终止流程，不可再发布。'
          })
      })
    } else if (record.status === 'approved') {
      items.push({
        key: 'publish',
        label: '上线',
        onClick: () =>
          void runAction(() => publishRelease(record.id), '已上线', {
            title: `上线“${record.title}”？`,
            content: '上线后该版本将对下游消费方生效。'
          })
      })
    } else if (record.status === 'released') {
      items.push({
        key: 'rollback',
        label: '回滚',
        danger: true,
        onClick: () =>
          void runAction(() => rollbackRelease(record.id), '已回滚', {
            title: `回滚“${record.title}”？`,
            content: '回滚后该版本将停止对下游生效，请确认影响范围。'
          })
      })
    }
    if (record.status === 'pending' || record.status === 'rejected') {
      if (record.status === 'pending') {
        items.push({ key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } })
      }
      items.push({
        key: 'delete', label: '删除', danger: true,
        onClick: () => modal.confirm({
          title: `删除发布单“${record.title}”？`, content: '删除后该发布记录将不可恢复。',
          okText: '确认删除', okButtonProps: { danger: true }, onOk: () => handleDelete(record)
        })
      })
    }
    return items
  }

  const columns: TableColumnsType<ReleaseRecord> = [
    {
      title: '发布单号',
      dataIndex: 'releaseNo',
      key: 'releaseNo',
      width: 170,
      render: (v: string) => <Typography.Text code>{v}</Typography.Text>
    },
    { title: '标题', dataIndex: 'title', key: 'title', width: 200, ellipsis: true },
    {
      title: '发布类型',
      dataIndex: 'releaseType',
      key: 'releaseType',
      width: 100,
      render: (v?: string) => {
        const meta = v ? typeMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    { title: '发布对象', dataIndex: 'targetName', key: 'targetName', width: 200, ellipsis: true },
    { title: '版本', dataIndex: 'targetVersion', key: 'targetVersion', width: 80, render: (v?: string) => v || '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v?: ReleaseStatus) => {
        const meta = v ? statusMeta[v] : undefined
        return meta ? <StatusBadge tone={meta.tone}>{meta.label}</StatusBadge> : '—'
      }
    },
    { title: '申请人', dataIndex: 'requestedBy', key: 'requestedBy', width: 100, render: (v?: string) => v || '—' },
    { title: '审批人', dataIndex: 'approvedBy', key: 'approvedBy', width: 100, render: (v?: string) => v || '—' },
    {
      title: '审批摘要', dataIndex: 'approvedDigest', key: 'approvedDigest', width: 130,
      render: (v?: string) => v ? <Typography.Text code copyable={{ text: v }}>{v.slice(0, 10)}…</Typography.Text> : '—'
    },
    { title: '上线时间', dataIndex: 'releasedAt', key: 'releasedAt', width: 170, render: (v?: string) => formatDateTime(v) },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v?: string) => formatDateTime(v) }
  ]

  if (canManage) {
    columns.push({
      title: '操作',
      key: 'action',
      width: 170,
      align: 'right',
      render: (_, record) => <RowActions items={actionsFor(record)} />
    })
  }

  return (
    <>
      <PageHeader
        description="管理数据开发产物的发布、审批与版本上线，保障变更可控可追溯。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新建发布单
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="发布单总数" value={String(stats.total)} icon={<CloudUploadOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="已上线" value={String(stats.released)} icon={<CloudUploadOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="待审批" value={String(stats.pending)} icon={<CloudUploadOutlined />} tone="orange" />
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
          <div className="filter-item">
            <span>发布类型</span>
            <Select
              allowClear
              placeholder="全部类型"
              value={typeFilter}
              onChange={setTypeFilter}
              className="filter-control-xl"
              options={Object.entries(typeMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="发布单列表">
        <Table<ReleaseRecord>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1400 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无发布单' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑发布单' : '新建发布单'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={620}
      >
        <Form form={form} layout="vertical" initialValues={{ releaseType: 'table' }} onFinish={(v) => void handleSubmit(v)}>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="releaseNo" label="发布单号" rules={[{ required: true, message: '请输入发布单号' }]}>
                <Input placeholder="如 REL-20260902-001" disabled={!!editing} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="releaseType" label="发布类型">
                <Select options={Object.entries(typeMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入标题' }]}>
            <Input placeholder="如 用户画像标签包 v2 上线" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="targetRefId" label="目标定义 ID">
                <InputNumber min={1} className="full-width-control" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="qualityRuleId" label="发布质量门禁">
                <Select allowClear placeholder="可选；审批后必须重新通过"
                  options={qualityRules.map((rule) => ({ value: rule.id, label: rule.name }))} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={16}>
              <Form.Item name="targetName" label="发布对象" rules={[{ required: true, message: '请输入发布对象' }]}>
                <Input placeholder="如 ads_user_profile" />
              </Form.Item>
            </Col>
            <Col span={8}>
              <Form.Item name="targetVersion" label="版本">
                <Input placeholder="如 v2" />
              </Form.Item>
            </Col>
          </Row>
          <Form.Item name="changeSummary" label="变更摘要">
            <Input.TextArea rows={3} placeholder="本次发布包含的变更内容与影响说明" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
