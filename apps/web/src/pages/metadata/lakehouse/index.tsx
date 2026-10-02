import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Alert, Button, Card, Col, Form, Input, Modal, Row, Select, Spin, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { BranchesOutlined, PlusOutlined, ReloadOutlined, TagsOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { EmptyState } from '@/components/FeedbackState'
import {
  createLakehouseSnapshotRef,
  deleteLakehouseSnapshotRef,
  listLakehouseMaintenanceJobs,
  listLakehouseSnapshots,
  listLakehouseSnapshotRefs,
  listLakehouseTables,
  moveLakehouseSnapshotRef,
  requestLakehouseMaintenanceJob,
  type LakehouseMaintenanceJob,
  type LakehouseMaintenanceOperation,
  type LakehouseSnapshotRef,
  type LakehouseTable,
  type LakehouseTableSnapshot
} from '@/api/metadata'

type RefFormValues = { refName: string; refType: 'branch' | 'tag'; snapshotId: string }

const maintenanceOptions: Array<{ value: LakehouseMaintenanceOperation; label: string }> = [
  { value: 'storage_estimate', label: '存储成本统计' },
  { value: 'rewrite_data_files', label: '小文件合并' },
  { value: 'expire_snapshots', label: '快照过期' },
  { value: 'remove_orphan_files', label: '孤儿文件清理' }
]

function jobTone(status: string) {
  if (status === 'succeeded') return 'success' as const
  if (status === 'failed') return 'danger' as const
  if (status === 'cancelled') return 'neutral' as const
  return 'warning' as const
}

export default function LakehouseControlPlanePage() {
  const { message } = App.useApp()
  const [tables, setTables] = useState<LakehouseTable[]>([])
  const [selectedId, setSelectedId] = useState<number>()
  const [snapshots, setSnapshots] = useState<LakehouseTableSnapshot[]>([])
  const [refs, setRefs] = useState<LakehouseSnapshotRef[]>([])
  const [jobs, setJobs] = useState<LakehouseMaintenanceJob[]>([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm<RefFormValues>()

  const selectedTable = useMemo(() => tables.find((table) => table.id === selectedId), [selectedId, tables])

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const nextTables = await listLakehouseTables()
      setTables(nextTables)
      const nextId = selectedId && nextTables.some((table) => table.id === selectedId) ? selectedId : nextTables[0]?.id
      setSelectedId(nextId)
      if (nextId) {
        const [nextSnapshots, nextRefs, nextJobs] = await Promise.all([
          listLakehouseSnapshots(nextId),
          listLakehouseSnapshotRefs(nextId),
          listLakehouseMaintenanceJobs(nextId)
        ])
        setSnapshots(nextSnapshots)
        setRefs(nextRefs)
        setJobs(nextJobs)
      } else {
        setSnapshots([])
        setRefs([])
        setJobs([])
      }
    } catch {
      message.error('湖仓控制面加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, selectedId])

  useEffect(() => { void refresh() }, [refresh])

  const createRef = async (values: RefFormValues) => {
    if (!selectedTable) return
    try {
      await createLakehouseSnapshotRef(selectedTable.id, values)
      message.success(`${values.refType === 'branch' ? '分支' : '标签'}已创建`)
      setModalOpen(false)
      form.resetFields()
      await refresh()
    } catch {
      message.error('引用创建失败，可能是快照不存在或标签受保护')
    }
  }

  const moveRef = async (ref: LakehouseSnapshotRef, snapshotId: string) => {
    try {
      await moveLakehouseSnapshotRef(ref.id, snapshotId)
      message.success('分支已移动')
      await refresh()
    } catch {
      message.error('分支移动失败；受保护标签不可移动')
    }
  }

  const deleteRef = (ref: LakehouseSnapshotRef) => {
    Modal.confirm({
      title: `删除${ref.refType === 'branch' ? '分支' : '标签'}「${ref.refName}」？`,
      content: ref.protected ? '受保护标签不可删除。' : '删除只移除引用，不删除快照数据。',
      okButtonProps: { danger: true, disabled: Boolean(ref.protected) },
      onOk: async () => {
        await deleteLakehouseSnapshotRef(ref.id)
        message.success('引用已删除')
        await refresh()
      }
    })
  }

  const requestMaintenance = async (operation: LakehouseMaintenanceOperation) => {
    if (!selectedTable) return
    try {
      await requestLakehouseMaintenanceJob(selectedTable.id, {
        operation,
        idempotencyKey: `ui-${operation}-${selectedTable.id}-${Date.now()}`
      })
      message.success('维护作业已登记')
      await refresh()
    } catch {
      message.error('维护作业登记失败')
    }
  }

  const snapshotColumns: TableColumnsType<LakehouseTableSnapshot> = [
    { title: 'Snapshot ID', dataIndex: 'snapshotId', key: 'snapshotId', width: 230, ellipsis: true },
    { title: '操作', dataIndex: 'operation', key: 'operation', width: 110, render: (value: string) => <Tag>{value}</Tag> },
    { title: '行数', dataIndex: 'rowCount', key: 'rowCount', width: 90, render: (value?: number) => value ?? '—' },
    { title: '文件数', dataIndex: 'fileCount', key: 'fileCount', width: 90, render: (value?: number) => value ?? '—' },
    { title: '提交时间', dataIndex: 'committedAt', key: 'committedAt', width: 190 }
  ]

  const refColumns: TableColumnsType<LakehouseSnapshotRef> = [
    { title: '名称', dataIndex: 'refName', key: 'refName', render: (value: string, ref) => <Typography.Text strong><span style={{ marginRight: 6 }}>{ref.refType === 'branch' ? <BranchesOutlined /> : <TagsOutlined />}</span>{value}</Typography.Text> },
    { title: '类型', dataIndex: 'refType', key: 'refType', width: 90, render: (value: string) => <Tag color={value === 'tag' ? 'gold' : 'blue'}>{value}</Tag> },
    { title: '指向快照', dataIndex: 'snapshotId', key: 'snapshotId', width: 230, ellipsis: true },
    { title: '状态', key: 'protected', width: 100, render: (_, ref) => ref.protected ? <StatusBadge tone="warning" dot={false}>受保护</StatusBadge> : <StatusBadge tone="success" dot={false}>可变更</StatusBadge> },
    { title: '操作', key: 'actions', width: 250, render: (_, ref) => <div style={{ display: 'flex', gap: 8 }}><Select<string> size="small" disabled={ref.refType === 'tag' && ref.protected} placeholder="移动到快照" style={{ width: 150 }} value={undefined} onChange={(value) => { if (value) void moveRef(ref, value) }} options={snapshots.map((snapshot) => ({ value: snapshot.snapshotId, label: snapshot.snapshotId }))} /><Button size="small" danger disabled={Boolean(ref.protected)} onClick={() => deleteRef(ref)}>删除</Button></div> }
  ]

  const jobColumns: TableColumnsType<LakehouseMaintenanceJob> = [
    { title: '作业', dataIndex: 'operation', key: 'operation', render: (value: string) => maintenanceOptions.find((item) => item.value === value)?.label ?? value },
    { title: '幂等键', dataIndex: 'idempotencyKey', key: 'idempotencyKey', ellipsis: true },
    { title: '状态', dataIndex: 'status', key: 'status', width: 110, render: (value: string) => <StatusBadge tone={jobTone(value)} dot={false}>{value}</StatusBadge> },
    { title: '请求时间', dataIndex: 'createdAt', key: 'createdAt', width: 190 }
  ]

  return <>
    <PageHeader description="管理 Iceberg 表快照、时间旅行引用、分支/标签和维护作业。受保护标签只能引用已有快照，删除引用不会删除快照数据。" extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>} />
    <PageToolbar actions={selectedTable && <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>新建分支 / 标签</Button>}>
      <Select aria-label="湖仓表" placeholder="选择湖仓表" value={selectedId} onChange={setSelectedId} style={{ minWidth: 360 }} options={tables.map((table) => ({ value: table.id, label: `${table.namespaceName}.${table.tableName}` }))} />
    </PageToolbar>
    <Spin spinning={loading}>
      {!selectedTable ? <EmptyState title="暂无湖仓表" description="先注册 Iceberg 表，再管理快照与引用。" /> : <>
        <Row gutter={[12, 12]} className="form-section-space">
          <Col xs={24} sm={8}><Card><Typography.Text type="secondary">表格式</Typography.Text><div><Tag color={selectedTable.formatVersion === 2 ? 'green' : 'orange'}>Iceberg v{selectedTable.formatVersion}</Tag><Typography.Text>{selectedTable.layer.toUpperCase()}</Typography.Text></div></Card></Col>
          <Col xs={24} sm={8}><Card><Typography.Text type="secondary">当前快照</Typography.Text><div><Typography.Text code>{selectedTable.currentSnapshotId || '—'}</Typography.Text></div></Card></Col>
          <Col xs={24} sm={8}><Card><Typography.Text type="secondary">保留策略</Typography.Text><div>{selectedTable.retentionDays} 天 · Schema v{selectedTable.currentSchemaVersion}</div></Card></Col>
        </Row>
        <DataSurface label={`快照与时间旅行 · ${snapshots.length} 个`}><Table rowKey="snapshotId" size="small" columns={snapshotColumns} dataSource={snapshots} pagination={false} scroll={{ x: 860 }} /></DataSurface>
        <DataSurface label={`分支与标签 · ${refs.length} 个`} className="form-section-space"><Table rowKey="id" size="small" columns={refColumns} dataSource={refs} pagination={false} scroll={{ x: 980 }} /></DataSurface>
        <DataSurface label="维护作业" className="form-section-space" extra={<Select<LakehouseMaintenanceOperation> placeholder="登记维护作业" style={{ width: 180 }} options={maintenanceOptions} onChange={(value) => void requestMaintenance(value)} value={undefined} />}><Table rowKey="id" size="small" columns={jobColumns} dataSource={jobs} pagination={false} scroll={{ x: 760 }} /></DataSurface>
        <Alert type="info" showIcon message="操作边界" description="页面只登记受管维护请求；真正执行、幂等、失败对账和快照清理仍由控制面 Runner 完成。" className="form-section-space" />
      </>}
    </Spin>
    <Modal title="新建湖仓引用" open={modalOpen} onCancel={() => setModalOpen(false)} onOk={() => void form.submit()} destroyOnHidden>
      <Form form={form} layout="vertical" initialValues={{ refType: 'branch', snapshotId: snapshots[0]?.snapshotId }} onFinish={(values) => void createRef(values)}>
        <Form.Item name="refName" label="名称" rules={[{ required: true, message: '请输入引用名称' }]}><Input placeholder="release_2026_10" /></Form.Item>
        <Form.Item name="refType" label="类型"><Select options={[{ value: 'branch', label: '分支（可移动）' }, { value: 'tag', label: '标签（默认保护）' }]} /></Form.Item>
        <Form.Item name="snapshotId" label="起始快照" rules={[{ required: true, message: '请选择已有快照' }]}><Select options={snapshots.map((snapshot) => ({ value: snapshot.snapshotId, label: snapshot.snapshotId }))} /></Form.Item>
      </Form>
    </Modal>
  </>
}
