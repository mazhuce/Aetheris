import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Col, Row, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CaretRightOutlined, EyeOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'
import {
  getFlinkHealth,
  listTasks,
  runTask,
  type FlinkGatewayHealth,
  type TaskInstanceStatus,
  type TaskView
} from '@/api/workflow'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'
import TaskDetailDrawer from '@/pages/workflow/components/TaskDetailDrawer'

/** Flink 运行页：复用任务详情中的 savepoint/resume/upgrade 生命周期操作。 */
export default function FlinkDevelopmentPage() {
  const { message } = App.useApp()
  const canManage = canManagePlatform()
  const [health, setHealth] = useState<FlinkGatewayHealth | null>(null)
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [loading, setLoading] = useState(false)
  const [running, setRunning] = useState<number | null>(null)
  const [detailId, setDetailId] = useState<number | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    const [healthResult, taskResult] = await Promise.allSettled([getFlinkHealth(), listTasks()])
    if (healthResult.status === 'fulfilled') setHealth(healthResult.value)
    else setHealth({ status: 'DOWN', message: 'Flink SQL Gateway 不可达' })
    if (taskResult.status === 'fulfilled') setTasks(taskResult.value.filter((view) => view.task.taskType === 'flink'))
    else message.error('Flink 任务列表加载失败')
    setLoading(false)
  }, [message])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(), 5000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const execute = async (view: TaskView) => {
    setRunning(view.task.id)
    try {
      await runTask(view.task.id, new Date().toISOString().slice(0, 10))
      message.success(`Flink 任务「${view.task.name}」已提交`)
      await refresh()
    } catch (error) {
      const detail = (error as { response?: { data?: { message?: string } } }).response?.data?.message
      message.error(detail || 'Flink 任务提交失败')
    } finally {
      setRunning(null)
    }
  }

  const statusTone = (status?: TaskInstanceStatus): StatusTone => {
    if (status === 'success') return 'success'
    if (status === 'failed' || status === 'timed_out') return 'danger'
    if (status === 'running' || status === 'submitting') return 'info'
    if (status === 'unknown' || status === 'cancel_requested') return 'warning'
    return 'neutral'
  }

  const columns = useMemo<TableColumnsType<TaskView>>(() => [
    {
      title: '任务名称',
      key: 'name',
      render: (_, view) => <Button type="link" className="compact-link" onClick={() => setDetailId(view.task.id)}>{view.task.name}</Button>
    },
    { title: '模式', key: 'mode', width: 110, render: (_, view) => <Tag>{view.task.flinkMode === 'streaming' ? 'Streaming' : 'Bounded'}</Tag> },
    {
      title: 'Gateway',
      key: 'endpoint',
      width: 260,
      ellipsis: true,
      render: (_, view) => <Tooltip title={view.task.flinkEndpoint}>{view.task.flinkEndpoint || '—'}</Tooltip>
    },
    {
      title: '最近状态',
      key: 'status',
      width: 110,
      render: (_, view) => view.lastStatus
        ? <StatusBadge tone={statusTone(view.lastStatus)}>{view.lastStatus}</StatusBadge>
        : <Tag>未运行</Tag>
    },
    { title: '最近完成', key: 'finished', width: 170, render: (_, view) => formatDateTime(view.lastFinishedAt) },
    {
      title: '操作',
      key: 'actions',
      width: 150,
      align: 'right',
      render: (_, view) => <Space>
        <Button size="small" icon={<EyeOutlined />} onClick={() => setDetailId(view.task.id)}>详情</Button>
        {canManage && <Button size="small" type="primary" icon={<CaretRightOutlined />} loading={running === view.task.id} onClick={() => void execute(view)}>运行</Button>}
      </Space>
    }
  ], [canManage, running])

  return <div className="workflow-list-page">
    <PageHeader
      description="管理 Flink SQL 流任务，查看 Gateway 健康状态，并从任务详情执行 checkpoint、savepoint、暂停、恢复和 replacement SQL 升级。"
      extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
    />
    <Row gutter={[16, 16]}>
      <Col xs={24} md={8}>
        <Card title="Flink SQL Gateway" size="small">
          <Space direction="vertical" size={4}>
            <StatusBadge tone={health?.status === 'UP' ? 'success' : 'danger'}>{health?.status || '检查中'}</StatusBadge>
            <span>{health?.version ? `Flink ${health.version}` : health?.message || '等待健康探测'}</span>
            {health?.endpoint && <Typography.Text type="secondary">{health.endpoint}</Typography.Text>}
          </Space>
        </Card>
      </Col>
      <Col xs={24} md={16}>
        <Card title={`Flink 任务 · ${tasks.length}`} size="small">
          <Typography.Text type="secondary">unknown 状态不会被当作成功；详细生命周期操作仍在任务详情中执行。</Typography.Text>
        </Card>
      </Col>
    </Row>
    <DataSurface>
      <Table rowKey={(view) => view.task.id} loading={loading} columns={columns} dataSource={tasks} pagination={{ pageSize: 10 }} scroll={{ x: 900 }} />
    </DataSurface>
    <TaskDetailDrawer taskId={detailId} onClose={() => setDetailId(null)} onChanged={() => void refresh()} />
  </div>
}
