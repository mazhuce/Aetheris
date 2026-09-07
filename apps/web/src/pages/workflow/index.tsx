import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Input, Select, Space, Table, Tag, Tooltip } from 'antd'
import type { TableColumnsType } from 'antd'
import {
  DeploymentUnitOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import { EmptyState } from '@/components/FeedbackState'
import {
  deleteTask,
  listTasks,
  INSTANCE_STATUS_META,
  type TaskView,
  type TaskInstanceStatus
} from '@/api/workflow'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'
import TaskFormModal from './components/TaskFormModal'
import TaskRunModal from './components/TaskRunModal'
import TaskDetailDrawer from './components/TaskDetailDrawer'

export default function WorkflowPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [data, setData] = useState<TaskView[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [statusFilter, setStatusFilter] = useState<string | undefined>()
  const [formOpen, setFormOpen] = useState(false)
  const [editing, setEditing] = useState<TaskView | null>(null)
  const [runTarget, setRunTarget] = useState<TaskView | null>(null)
  const [detailId, setDetailId] = useState<number | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setData(await listTasks())
    } catch {
      message.error('任务列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const filtered = useMemo(
    () =>
      data.filter((view) => {
        if (keyword
          && !view.task.name.includes(keyword)
          && !(view.task.targetTable ?? '').includes(keyword)) {
          return false
        }
        if (statusFilter && view.task.status !== statusFilter) return false
        return true
      }),
    [data, keyword, statusFilter]
  )

  const handleDelete = (view: TaskView) => {
    modal.confirm({
      title: '删除任务',
      // 说明连带影响：级联删除会带走实例与日志，这是不可逆的。
      content: `将同时删除「${view.task.name}」的运行实例、日志与依赖关系，且会取消其定时调度。此操作不可恢复。`,
      okText: '确认删除',
      okButtonProps: { danger: true },
      onOk: async () => {
        try {
          await deleteTask(view.task.id)
          message.success('任务已删除')
          await refresh()
        } catch {
          message.error('删除失败')
        }
      }
    })
  }

  const statusTone = (status: TaskInstanceStatus): StatusTone => {
    if (status === 'success') return 'success'
    if (status === 'failed') return 'danger'
    if (status === 'skipped') return 'warning'
    if (status === 'running') return 'info'
    return 'neutral'
  }

  const columns: TableColumnsType<TaskView> = [
    {
      title: '任务名称',
      key: 'name',
      width: 200,
      render: (_, view) => (
        <Button type="link" className="compact-link" onClick={() => setDetailId(view.task.id)}>
          {view.task.name}
        </Button>
      )
    },
    {
      title: '目标表',
      key: 'targetTable',
      width: 180,
      render: (_, view) => view.task.targetTable || '—'
    },
    {
      title: '数据源',
      key: 'datasource',
      width: 160,
      render: (_, view) => view.task.datasourceName || `#${view.task.datasourceId}`
    },
    {
      title: '调度',
      key: 'cron',
      width: 150,
      render: (_, view) =>
        view.task.cronExpression
          ? <Tooltip title={`下次触发：${formatDateTime(view.nextFireTime)}`}>
              <Tag>{view.task.cronExpression}</Tag>
            </Tooltip>
          : <Tag>仅手动</Tag>
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (_, view) =>
        view.task.status === 'enabled'
          ? <StatusBadge tone="success">启用</StatusBadge>
          : <StatusBadge tone="neutral">停用</StatusBadge>
    },
    {
      title: '最近结果',
      key: 'lastStatus',
      width: 110,
      render: (_, view) => {
        if (!view.lastStatus) return '—'
        const meta = INSTANCE_STATUS_META[view.lastStatus as TaskInstanceStatus]
        return (
          <Tooltip title={`业务日期 ${view.lastBizDate ?? '—'}`}>
            <StatusBadge tone={statusTone(view.lastStatus as TaskInstanceStatus)}>{meta?.label ?? view.lastStatus}</StatusBadge>
          </Tooltip>
        )
      }
    },
    {
      title: '上游依赖',
      key: 'upstream',
      width: 90,
      render: (_, view) =>
        view.upstreamCount > 0 ? <Tag>{view.upstreamCount} 个</Tag> : '—'
    },
    {
      title: '最近完成',
      key: 'lastFinishedAt',
      width: 170,
      render: (_, view) => formatDateTime(view.lastFinishedAt)
    },
    {
      title: '操作',
      key: 'action',
      width: 96,
      align: 'right',
      fixed: 'right',
      render: (_, view) => (
        <RowActions
          primary={canManage ? (
            <Tooltip title="运行任务">
              <Button type="text" size="small" icon={<PlayCircleOutlined />} aria-label={`运行任务 ${view.task.name}`} onClick={() => setRunTarget(view)} />
            </Tooltip>
          ) : undefined}
          items={canManage ? [
            { key: 'edit', label: '编辑', onClick: () => { setEditing(view); setFormOpen(true) } },
            { key: 'delete', label: '删除', danger: true, onClick: () => handleDelete(view) }
          ] : []}
        />
      )
    }
  ]

  return (
    <div className="workflow-list-page">
      <PageHeader
        description="定义数据加工任务并按计划周期调度执行；同一任务在同一业务日期仅保留一个实例，重跑将覆盖目标分区数据。"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
            {canManage ? (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={() => { setEditing(null); setFormOpen(true) }}
              >
                新建任务
              </Button>
            ) : <Tag>只读访问</Tag>}
          </Space>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>任务/目标表</span>
            <Input
              placeholder="搜索任务名或目标表"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              allowClear
              className="filter-control-wide"
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              value={statusFilter}
              onChange={setStatusFilter}
              allowClear
              className="filter-control-sm"
              options={[
                { value: 'enabled', label: '启用' },
                { value: 'disabled', label: '停用' }
              ]}
            />
          </div>
        </div>
      </PageToolbar>

      <DataSurface label="调度任务列表">
        <Table<TaskView>
          size="small"
          rowKey={(view) => view.task.id}
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1340 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{
            emptyText: (
              <EmptyState
                compact
                icon={<DeploymentUnitOutlined />}
                title="暂无任务"
                description="新建任务时可用 ${bizDate} 引用业务日期，无需为补数据修改 SQL。"
              />
            )
          }}
        />
      </DataSurface>

      <TaskFormModal
        open={formOpen}
        editing={editing}
        onClose={() => { setFormOpen(false); setEditing(null) }}
        onSaved={() => { setFormOpen(false); setEditing(null); void refresh() }}
      />
      <TaskRunModal
        target={runTarget}
        onClose={() => setRunTarget(null)}
        onFinished={() => { setRunTarget(null); void refresh() }}
      />
      <TaskDetailDrawer
        taskId={detailId}
        onClose={() => setDetailId(null)}
        onChanged={() => void refresh()}
      />
    </div>
  )
}
