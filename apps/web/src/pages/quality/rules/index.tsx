import { useCallback, useEffect, useState } from 'react'
import { App, Button, Form, Input, InputNumber, Modal, Select, Table, Tag, Tooltip } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlayCircleOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import FixedExecutionApprovalDrawer from '@/components/FixedExecutionApprovalDrawer'
import { executionApprovalError } from '@/utils/executionApprovalError'
import {
  createRule,
  deleteRule,
  listRulesPage,
  runRule,
  updateRule,
  type QualityRule,
  type QualityRuleInput
} from '@/api/quality'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'
import { listDataSources, type DataSource } from '@/api/metadata'
import { useResourceActions } from '@/hooks/useResourceActions'
import { useVisibleListPage } from '@/hooks/useVisibleListPage'

const severityMeta: Record<string, { color: string; text: string }> = {
  low: { color: 'default', text: '低' },
  medium: { color: 'warning', text: '中' },
  high: { color: 'error', text: '高' }
}

export default function QualityRulePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [keyword, setKeyword] = useState('')
  const [ruleType, setRuleType] = useState<string>()
  const [status, setStatus] = useState<string>()
  const queryKey = JSON.stringify({ keyword, ruleType, status })
  const listing = useVisibleListPage(
    (cursor, signal) => listRulesPage({ cursor, size: 50, signal }, { keyword: keyword || undefined, ruleType, status }),
    queryKey
  )
  const allData = listing.items
  const permissions = useResourceActions('quality_rule', allData)
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<QualityRule | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [approvalId, setApprovalId] = useState<number | null>(null)
  const [approvalRefresh, setApprovalRefresh] = useState(0)
  const [form] = Form.useForm<QualityRuleInput>()

  const refresh = useCallback(() => listing.refresh(), [listing])

  useEffect(() => {
    if (canManage) void listDataSources().then(setDataSources).catch(() => message.error('数据源列表加载失败'))
  }, [message, canManage])

  useEffect(() => {
    if (open) {
      if (editing) form.setFieldsValue(editing)
      else form.resetFields()
    }
  }, [open, editing, form])

  const filtered = allData

  const handleSubmit = async (values: QualityRuleInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateRule(editing.id, canManage ? values : { name: values.name, description: values.description, status: values.status, cronExpression: values.cronExpression })
        if (approvalId === editing.id) setApprovalRefresh((value) => value + 1)
        message.success('规则已更新')
      } else {
        await createRule(values)
        message.success('规则已添加')
      }
      setOpen(false)
      setEditing(null)
      refresh()
    } catch (error) {
      message.error(executionApprovalError(error, '保存失败'))
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteRule(id)
      message.success('规则已删除')
      refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const handleRun = async (id: number) => {
    try {
      const result = await runRule(id)
      if (result.status === 'passed') message.success('质量检查通过')
      else if (result.status === 'failed') message.warning(`质量检查未通过，发现 ${result.violationCount ?? 0} 条违规数据`)
      else message.warning(result.status === 'unknown' ? '检查结果待对账，请核实运行记录；不会自动重试' : '本次请求未确认成功，请查看运行记录')
      refresh()
    } catch (error) {
      message.error(executionApprovalError(error, '质量检查未确认成功，请查看运行记录'))
      refresh()
    }
  }

  const dataSourceName = (id?: number) => dataSources.find((source) => source.id === id)?.name ?? '待配置'

  const columns: TableColumnsType<QualityRule> = [
    { title: '规则名称', dataIndex: 'name', key: 'name', width: 180, render: (_, record) => <Button type="link" size="small" aria-label={`查看执行批准 ${record.name}`} onClick={() => setApprovalId(record.id)}>{record.name}</Button> },
    { title: '规则类型', dataIndex: 'ruleType', key: 'ruleType', width: 120, render: (value: string) => <Tag>{value}</Tag> },
    { title: '数据源', dataIndex: 'datasourceId', key: 'datasourceId', width: 140, render: (value?: number) => dataSourceName(value) },
    { title: '目标表', dataIndex: 'targetTable', key: 'targetTable', width: 140 },
    { title: '表达式', dataIndex: 'expression', key: 'expression', ellipsis: true },
    { title: '阈值', dataIndex: 'threshold', key: 'threshold', width: 80, render: (value?: number) => value ?? '—' },
    {
      title: '严重程度',
      dataIndex: 'severity',
      key: 'severity',
      width: 100,
      render: (value?: string) => {
        const meta = severityMeta[value ?? ''] ?? { color: 'default', text: value ?? '—' }
        const tone: StatusTone = value === 'high' ? 'danger' : value === 'medium' ? 'warning' : 'neutral'
        return <StatusBadge tone={tone}>{meta.text}</StatusBadge>
      }
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value?: string) => <StatusBadge tone={value === 'enabled' ? 'success' : 'neutral'}>{value === 'enabled' ? '启用' : '禁用'}</StatusBadge>
    },
    {
      title: '最近检查',
      dataIndex: 'lastCheckStatus',
      key: 'lastCheckStatus',
      width: 110,
      render: (value?: string) => value
        ? <StatusBadge tone={value === 'passed' ? 'success' : value === 'failed' ? 'danger' : 'warning'}>{({ passed: '通过', failed: '未通过', unknown: '待对账', error: '执行错误', running: '执行中' } as Record<string, string>)[value] ?? value}</StatusBadge>
        : '—'
    },
    { title: '违规数', dataIndex: 'lastViolationCount', key: 'lastViolationCount', width: 80, render: (value?: number) => value ?? '—' },
    { title: '检查时间', dataIndex: 'lastCheckedAt', key: 'lastCheckedAt', width: 170, render: (value?: string) => formatDateTime(value) }
  ]

  if (canManage || allData.some((rule) => ['execute', 'update', 'delete'].some((action) => permissions.can(action as 'execute' | 'update' | 'delete', rule.id)))) columns.push({
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      fixed: 'right',
      render: (_, record) => (
        <RowActions primary={(canManage || permissions.can('execute', record.id)) && (
          <Tooltip title={record.datasourceId ? '立即检查' : '请先配置数据源'}>
            <Button type="text" size="small" icon={<PlayCircleOutlined />} aria-label={`检查规则 ${record.name}`} disabled={!record.datasourceId} onClick={() => void handleRun(record.id)} />
          </Tooltip>
        )} items={[
          ...((canManage || permissions.can('update', record.id)) ? [{ key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } }] : []),
          ...((canManage || permissions.can('delete', record.id)) ? [{
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () => modal.confirm({
              title: `删除规则“${record.name}”？`,
              content: '删除后该质量校验将不再执行，此操作不可恢复。',
              okText: '确认删除',
              okButtonProps: { danger: true },
              onOk: () => handleDelete(record.id)
            })
          }] : [])
        ]} />
      )
    })

  return (
    <>
      <PageHeader
        description="配置数据质量校验规则，覆盖空值、重复与自定义 SQL 等校验场景。"
        extra={canManage ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增规则
          </Button>
        ) : <Tag>按资源授权</Tag>}
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>规则/表</span>
            <Input placeholder="搜索规则名或目标表" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-2xl" />
          </div>
          <div className="filter-item">
            <span>类型</span>
            <Select value={ruleType} onChange={setRuleType} allowClear placeholder="全部" className="filter-control-md"
              options={[{ value: 'not_null', label: '空值检查' }, { value: 'duplicate', label: '重复检查' }, { value: 'range', label: '范围检查' }, { value: 'enum', label: '枚举检查' }, { value: 'sql', label: '自定义 SQL' }]} />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select value={status} onChange={setStatus} allowClear placeholder="全部" className="filter-control-xs"
              options={[{ value: 'enabled', label: '启用' }, { value: 'disabled', label: '禁用' }]} />
          </div>
          <Button icon={<ReloadOutlined />} onClick={refresh}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="质量规则列表">
        <Table<QualityRule>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={listing.loading}
          scroll={{ x: 1100 }}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: listing.error ?? (listing.hasMore ? '暂未找到可见规则，可继续查看' : '暂无质量规则') }}
        />
      </DataSurface>
      <div className="table-pagination-bar">
        <Button disabled={!listing.canPrevious || listing.loading} onClick={listing.previous}>上一页</Button>
        <span>第 {listing.pageNumber} 页 · 本页 {listing.items.length} 条</span>
        <Button disabled={!listing.hasMore || listing.loading || !!listing.error} onClick={listing.next}>下一页</Button>
      </div>

      <FixedExecutionApprovalDrawer kind="quality" resourceId={approvalId} refreshToken={approvalRefresh}
        canApprove={approvalId !== null && (canManage || permissions.can('update', approvalId))}
        canBindCron={approvalId !== null && (canManage || permissions.can('execute', approvalId))}
        onClose={() => setApprovalId(null)} />
      <Modal
        title={editing ? '编辑规则' : '新增规则'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={560}
      >
        <Form form={form} layout="vertical" initialValues={{ ruleType: 'not_null', severity: 'medium', status: 'enabled' }} onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="name" label="规则名称" rules={[{ required: true, message: '请输入规则名称' }]}>
            <Input placeholder="例如：订单表空值检查" />
          </Form.Item>
          <Form.Item name="ruleType" label="规则类型" rules={[{ required: canManage, message: '请选择类型' }]}>
            <Select disabled={!canManage} options={[{ value: 'not_null', label: '空值检查' }, { value: 'duplicate', label: '重复检查' }, { value: 'range', label: '范围检查' }, { value: 'enum', label: '枚举检查' }, { value: 'sql', label: '自定义 SQL' }]} />
          </Form.Item>
          <Form.Item name="datasourceId" label="数据源" rules={[{ required: canManage, message: '请选择数据源' }]}>
            <Select disabled={!canManage} showSearch optionFilterProp="label" options={dataSources.map((source) => ({ value: source.id, label: `${source.name} · ${source.type}` }))} />
          </Form.Item>
          <Form.Item name="targetTable" label="目标表" rules={[{ required: canManage, message: '请输入目标表' }]}>
            <Input disabled={!canManage} placeholder="例如：orders" />
          </Form.Item>
          <Form.Item name="expression" label="有效数据表达式 / SQL" rules={[{ required: canManage, message: '请输入规则表达式' }]} extra="空值、范围、枚举填写有效数据应满足的条件；重复检查填写逗号分隔字段；自定义 SQL 第一列必须返回违规数。">
            <Input.TextArea disabled={!canManage} rows={3} placeholder="例如：order_id IS NOT NULL" />
          </Form.Item>
          <Form.Item name="threshold" label="阈值">
            <InputNumber disabled={!canManage} min={0} className="full-width-control" placeholder="可选" />
          </Form.Item>
          <Form.Item name="severity" label="严重程度">
            <Select disabled={!canManage} options={[{ value: 'low', label: '低' }, { value: 'medium', label: '中' }, { value: 'high', label: '高' }]} />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select options={[{ value: 'enabled', label: '启用', disabled: !canManage && !!editing && !permissions.can('execute', editing.id) }, { value: 'disabled', label: '禁用' }]} />
          </Form.Item>
          <Form.Item name="cronExpression" label="定时调度" extra="Quartz cron（6/7 段），例如 0 0 2 * * ? 表示每天凌晨 2 点；留空表示仅手动执行。">
            <Input disabled={!canManage && !!editing && !permissions.can('execute', editing.id)} placeholder="例如：0 0 2 * * ?" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
