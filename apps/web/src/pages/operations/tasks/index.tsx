import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, DashboardOutlined, FieldTimeOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import StatCard from '@/components/StatCard'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import { listAllInstances, listTasks, INSTANCE_STATUS_META, type TaskInstance, type TaskView } from '@/api/workflow'
import { formatDateTime } from '@/utils/format'

export default function TaskMonitorPage() {
  const { message } = App.useApp()
  const [instances, setInstances] = useState<TaskInstance[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<string>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [instanceList, taskList] = await Promise.all([
        listAllInstances(status, 100),
        listTasks()
      ])
      setInstances(instanceList)
      setTasks(taskList)
    } catch {
      message.error('任务实例加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, status])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const taskName = useCallback(
    (taskId: number) => tasks.find((view) => view.task.id === taskId)?.task.name ?? `任务 ${taskId}`,
    [tasks]
  )

  const metrics = useMemo(() => {
    const finished = instances.filter((item) => item.status === 'success' || item.status === 'failed')
    const success = instances.filter((item) => item.status === 'success').length
    const failed = instances.filter((item) => item.status === 'failed').length
    const successRate = finished.length ? Math.round((success / finished.length) * 100) : 100
    const withDuration = instances.filter((item) => item.durationMs !== undefined && item.durationMs !== null)
    const averageDuration = withDuration.length
      ? Math.round(withDuration.reduce((total, item) => total + (item.durationMs ?? 0), 0) / withDuration.length)
      : 0
    return { success, failed, successRate, averageDuration }
  }, [instances])

  const columns: TableColumnsType<TaskInstance> = [
    {
      title: '任务',
      dataIndex: 'taskId',
      key: 'taskId',
      width: 200,
      render: (value: number) => taskName(value)
    },
    { title: '业务日期', dataIndex: 'bizDate', key: 'bizDate', width: 120 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value: TaskInstance['status']) => {
        const meta = INSTANCE_STATUS_META[value] ?? { label: value, color: 'default' }
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '触发',
      dataIndex: 'triggerType',
      key: 'triggerType',
      width: 90,
      render: (value?: string) => (value === 'schedule' ? '调度' : value === 'manual' ? '手动' : value ?? '—')
    },
    { title: '触发人', dataIndex: 'triggeredBy', key: 'triggeredBy', width: 110, render: (value?: string) => value || '—' },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (value?: number) => (value === undefined || value === null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(1)} s` : `${value} ms`)
    },
    {
      title: '行数',
      dataIndex: 'affectedRows',
      key: 'affectedRows',
      width: 90,
      render: (value?: number) => (value === undefined || value === null ? '—' : value < 0 ? '未知' : value.toLocaleString())
    },
    { title: '开始时间', dataIndex: 'startedAt', key: 'startedAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '错误信息',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (value?: string) =>
        value ? <Typography.Text type="danger" style={{ fontSize: 13 }}>{value}</Typography.Text> : '—'
    }
  ]

  return (
    <div className="task-monitor-page">
      <PageHeader
        description="监控调度任务的运行状态、成功率与耗时，失败实例可查看错误信息并跳转处理。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新数据</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><StatCard title="成功实例" value={String(metrics.success)} icon={<CheckCircleOutlined />} footer="最近 100 次运行" tone="green" /></Col>
        <Col xs={12} lg={6}><StatCard title="失败实例" value={String(metrics.failed)} icon={<CloseCircleOutlined />} footer="需要人工介入" tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="成功率" value={`${metrics.successRate}%`} icon={<DashboardOutlined />} footer="仅统计已结束实例" tone="neutral" /></Col>
        <Col xs={12} lg={6}><StatCard title="平均耗时" value={metrics.averageDuration >= 1000 ? `${(metrics.averageDuration / 1000).toFixed(1)} s` : `${metrics.averageDuration} ms`} icon={<FieldTimeOutlined />} footer="当前列表范围" tone="orange" /></Col>
      </Row>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>状态</span>
            <Select
              value={status}
              onChange={setStatus}
              placeholder="全部"
              allowClear
              className="filter-control-sm"
              options={Object.entries(INSTANCE_STATUS_META).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
        </div>
      </PageToolbar>

      <DataSurface title="运行实例" description="最近 100 次任务运行" label="任务实例明细">
        <Table<TaskInstance>
          rowKey="id"
          columns={columns}
          dataSource={instances}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无运行记录，任务触发后会自动出现在这里' }}
        />
      </DataSurface>
    </div>
  )
}
