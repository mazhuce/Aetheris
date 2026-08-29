import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Input, Select, Space, Table, Tag, Tooltip } from 'antd'
import type { TableColumnsType } from 'antd'
import {
  DeploymentUnitOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  deleteTask,
  listTasks,
  INSTANCE_STATUS_META,
  type TaskView,
  type TaskInstanceStatus
} from '@/api/workflow'
import { formatDateTime } from '@/utils/format'
import TaskFormModal from './components/TaskFormModal'
import TaskRunModal from './components/TaskRunModal'
import TaskDetailDrawer from './components/TaskDetailDrawer'

export default function WorkflowPage() {
  const { message, modal } = App.useApp()
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

  const columns: TableColumnsType<TaskView> = [
    {
      title: '任务名称',
      key: 'name',
      width: 200,
      render: (_, view) => (
        <Button type="link" style={{ padding: 0 }} onClick={() => setDetailId(view.task.id)}>
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
              <Tag color="blue">{view.task.cronExpression}</Tag>
            </Tooltip>
          : <Tag>仅手动</Tag>
    },
    {
      title: '状态',
      key: 'status',
      width: 90,
      render: (_, view) =>
        view.task.status === 'enabled'
          ? <Tag color="success">启用</Tag>
          : <Tag>停用</Tag>
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
            <Tag color={meta?.color}>{meta?.label ?? view.lastStatus}</Tag>
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
      width: 190,
      fixed: 'right',
      render: (_, view) => (
        <Space size={0}>
          <Button
            type="link"
            size="small"
            icon={<PlayCircleOutlined />}
            onClick={() => setRunTarget(view)}
          >
            运行
          </Button>
          <Button type="link" size="small" onClick={() => { setEditing(view); setFormOpen(true) }}>
            编辑
          </Button>
          <Button type="link" size="small" danger onClick={() => handleDelete(view)}>
            删除
          </Button>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="任务编排"
        description="定义数仓加工任务并按 cron 调度执行。同一任务同一业务日期只保留一个实例，重跑会覆盖目标分区而非追加数据。"
        extra={
          <Space>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => { setEditing(null); setFormOpen(true) }}
            >
              新建任务
            </Button>
          </Space>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>任务/目标表</span>
            <Input
              placeholder="搜索任务名或目标表"
              value={keyword}
              onChange={(e) => setKeyword(e.target.value)}
              allowClear
              style={{ width: 220 }}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              value={statusFilter}
              onChange={setStatusFilter}
              allowClear
              style={{ width: 120 }}
              options={[
                { value: 'enabled', label: '启用' },
                { value: 'disabled', label: '停用' }
              ]}
            />
          </div>
        </div>
      </Card>

      <Card size="small">
        <Table<TaskView>
          size="small"
          bordered
          rowKey={(view) => view.task.id}
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1340 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{
            emptyText: (
              <div style={{ padding: '24px 0', color: '#8c8c8c' }}>
                <DeploymentUnitOutlined style={{ fontSize: 28, marginBottom: 8 }} />
                <div>暂无任务</div>
                <div style={{ fontSize: 12, marginTop: 4 }}>
                  新建任务时可用 <code>{'${bizDate}'}</code> 引用业务日期，无需为补数据修改 SQL。
                </div>
              </div>
            )
          }}
        />
      </Card>

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
    </>
  )
}
