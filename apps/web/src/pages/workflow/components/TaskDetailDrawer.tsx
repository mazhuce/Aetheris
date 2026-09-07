import { useCallback, useEffect, useState } from 'react'
import {
  App,
  Button,
  Descriptions,
  Drawer,
  Empty,
  Popconfirm,
  Select,
  Space,
  Spin,
  Table,
  Tabs,
  Tag,
  Timeline,
  Tooltip
} from 'antd'
import type { TableColumnsType } from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  addDependency,
  getTask,
  listDependencies,
  listInstanceLogs,
  listInstances,
  listTasks,
  removeDependency,
  INSTANCE_STATUS_META,
  type TaskDependency,
  type TaskInstance,
  type TaskLog,
  type TaskView
} from '@/api/workflow'
import { formatDateTime } from '@/utils/format'
import StatusBadge, { type StatusTone } from '@/components/StatusBadge'

interface Props {
  taskId: number | null
  onClose: () => void
  onChanged: () => void
}

/** 日志级别配色，与实例状态的语义保持一致。 */
const LOG_LEVEL_COLOR: Record<string, string> = {
  info: 'default',
  warn: 'orange',
  error: 'red'
}

export default function TaskDetailDrawer({ taskId, onClose, onChanged }: Props) {
  const { message } = App.useApp()
  const [view, setView] = useState<TaskView | null>(null)
  const [instances, setInstances] = useState<TaskInstance[]>([])
  const [dependencies, setDependencies] = useState<TaskDependency[]>([])
  const [allTasks, setAllTasks] = useState<TaskView[]>([])
  const [logs, setLogs] = useState<TaskLog[]>([])
  const [selectedInstance, setSelectedInstance] = useState<number | null>(null)
  const [loading, setLoading] = useState(false)
  const [upstreamToAdd, setUpstreamToAdd] = useState<number | undefined>()

  const refresh = useCallback(async () => {
    if (taskId == null) return
    setLoading(true)
    try {
      const [detail, instanceList, dependencyList] = await Promise.all([
        getTask(taskId),
        listInstances(taskId),
        listDependencies(taskId)
      ])
      setView(detail)
      setInstances(instanceList)
      setDependencies(dependencyList)
    } catch {
      message.error('任务详情加载失败')
    } finally {
      setLoading(false)
    }
  }, [taskId, message])

  useEffect(() => {
    if (taskId == null) {
      setView(null)
      setInstances([])
      setLogs([])
      setSelectedInstance(null)
      return
    }
    void refresh()
  }, [taskId, refresh])

  const openLogs = async (instanceId: number) => {
    setSelectedInstance(instanceId)
    try {
      setLogs(await listInstanceLogs(instanceId))
    } catch {
      message.error('日志加载失败')
      setLogs([])
    }
  }

  const loadCandidates = async () => {
    try {
      setAllTasks(await listTasks())
    } catch {
      message.error('任务列表加载失败')
    }
  }

  const handleAddDependency = async () => {
    if (taskId == null || upstreamToAdd == null) return
    try {
      await addDependency(taskId, upstreamToAdd)
      message.success('依赖已添加')
      setUpstreamToAdd(undefined)
      await refresh()
      onChanged()
    } catch (error) {
      // 后端会拒绝环形依赖与自依赖，原因需要透出，否则用户不知为何失败。
      const detail = (error as { response?: { data?: { message?: string } } })
        ?.response?.data?.message
      message.error(detail || '添加依赖失败')
    }
  }

  const handleRemoveDependency = async (upstreamTaskId: number) => {
    if (taskId == null) return
    try {
      await removeDependency(taskId, upstreamTaskId)
      message.success('依赖已移除')
      await refresh()
      onChanged()
    } catch {
      message.error('移除依赖失败')
    }
  }

  const instanceTone = (status: TaskInstance['status']): StatusTone => {
    if (status === 'success') return 'success'
    if (status === 'failed') return 'danger'
    if (status === 'skipped') return 'warning'
    if (status === 'running') return 'info'
    return 'neutral'
  }

  const instanceColumns: TableColumnsType<TaskInstance> = [
    { title: '业务日期', dataIndex: 'bizDate', key: 'bizDate', width: 110 },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v: TaskInstance['status']) => (
        <StatusBadge tone={instanceTone(v)}>
          {INSTANCE_STATUS_META[v]?.label ?? v}
        </StatusBadge>
      )
    },
    {
      title: '触发',
      dataIndex: 'triggerType',
      key: 'triggerType',
      width: 80,
      render: (v?: string) => {
        if (v === 'schedule') return <Tag>定时</Tag>
        if (v === 'retry') return <Tag>重试</Tag>
        return <Tag>手动</Tag>
      }
    },
    {
      title: '尝试',
      dataIndex: 'attempt',
      key: 'attempt',
      width: 60,
      render: (v?: number) => v ?? '—'
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 90,
      render: (v?: number) => (v == null ? '—' : `${v} ms`)
    },
    {
      title: '影响行数',
      dataIndex: 'affectedRows',
      key: 'affectedRows',
      width: 100,
      // -1 是「引擎未返回行数」，显示成 0 会被误读为「没写入数据」。
      render: (v?: number) =>
        v == null || v < 0 ? <Tooltip title="引擎未返回行数">未知</Tooltip> : v
    },
    {
      title: '原因',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (v?: string) =>
        v
          ? <span className="result-error">{v}</span>
          : '—'
    },
    {
      title: '操作',
      key: 'action',
      width: 90,
      render: (_, record) => (
        <Button type="link" size="small" onClick={() => void openLogs(record.id)}>
          查看日志
        </Button>
      )
    }
  ]

  const taskNameOf = (id: number) =>
    allTasks.find((t) => t.task.id === id)?.task.name ?? `#${id}`

  return (
    <Drawer
      title={view?.task.name ?? '任务详情'}
      open={taskId !== null}
      onClose={onClose}
      width="min(100vw, 960px)"
      extra={
        <Button icon={<ReloadOutlined />} size="small" onClick={() => void refresh()}>
          刷新
        </Button>
      }
    >
      <Spin spinning={loading}>
        {view && (
          <Tabs
            defaultActiveKey="overview"
            items={[
              {
                key: 'overview',
                label: '概览',
                children: (
                  <>
                    <Descriptions className="detail-descriptions" size="small" column={2}>
                      <Descriptions.Item label="任务名称" span={2}>
                        {view.task.name}
                      </Descriptions.Item>
                      <Descriptions.Item label="目标表">
                        {view.task.targetTable || '—'}
                      </Descriptions.Item>
                      <Descriptions.Item label="数据源">
                        {view.task.datasourceName || `#${view.task.datasourceId}`}
                      </Descriptions.Item>
                      <Descriptions.Item label="状态">
                        {view.task.status === 'enabled'
                          ? <StatusBadge tone="success">启用</StatusBadge>
                          : <StatusBadge tone="neutral">停用</StatusBadge>}
                      </Descriptions.Item>
                      <Descriptions.Item label="调度">
                        {view.task.cronExpression
                          ? <Tag>{view.task.cronExpression}</Tag>
                          : <Tag>仅手动</Tag>}
                      </Descriptions.Item>
                      <Descriptions.Item label="下次触发">
                        {formatDateTime(view.nextFireTime)}
                      </Descriptions.Item>
                      <Descriptions.Item label="超时/重试">
                        {view.task.timeoutSeconds ?? '—'} 秒 / {view.task.maxRetries ?? 0} 次
                      </Descriptions.Item>
                      <Descriptions.Item label="负责人">
                        {view.task.owner || '—'}
                      </Descriptions.Item>
                      <Descriptions.Item label="更新时间">
                        {formatDateTime(view.task.updatedAt)}
                      </Descriptions.Item>
                      <Descriptions.Item label="说明" span={2}>
                        {view.task.description || '—'}
                      </Descriptions.Item>
                      <Descriptions.Item label="加工 SQL" span={2}>
                        <pre className="code-panel">
                          {view.task.taskSql}
                        </pre>
                      </Descriptions.Item>
                    </Descriptions>
                  </>
                )
              },
              {
                key: 'instances',
                label: `运行实例（${instances.length}）`,
                children: (
                  <>
                    <Table<TaskInstance>
                      size="small"
                      rowKey="id"
                      columns={instanceColumns}
                      dataSource={instances}
                      pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
                      locale={{ emptyText: '暂无运行记录' }}
                    />
                    {selectedInstance !== null && (
                      <div className="log-panel">
                        <div className="log-panel-title">
                          实例 #{selectedInstance} 执行日志
                        </div>
                        {logs.length === 0 ? (
                          <Empty description="暂无日志" />
                        ) : (
                          <Timeline
                            items={logs.map((entry) => ({
                              color: LOG_LEVEL_COLOR[entry.level] ?? 'gray',
                              children: (
                                <div>
                                  <Space size={8}>
                                    <Tag color={LOG_LEVEL_COLOR[entry.level]}>{entry.level}</Tag>
                                    <span className="log-time">
                                      {formatDateTime(entry.createdAt)}
                                    </span>
                                  </Space>
                                  <div className="log-message">{entry.message}</div>
                                </div>
                              )
                            }))}
                          />
                        )}
                      </div>
                    )}
                  </>
                )
              },
              {
                key: 'dependencies',
                label: `上游依赖（${dependencies.length}）`,
                children: (
                  <>
                    <Space className="dependency-toolbar">
                      <Select
                        placeholder="选择上游任务"
                        className="dependency-select"
                        value={upstreamToAdd}
                        onChange={setUpstreamToAdd}
                        onDropdownVisibleChange={(open) => { if (open) void loadCandidates() }}
                        options={allTasks
                          .filter((t) => t.task.id !== taskId)
                          .map((t) => ({ value: t.task.id, label: t.task.name }))}
                      />
                      <Button
                        type="primary"
                        icon={<PlusOutlined />}
                        disabled={upstreamToAdd == null}
                        onClick={() => void handleAddDependency()}
                      >
                        添加依赖
                      </Button>
                    </Space>

                    {dependencies.length === 0 ? (
                      <Empty
                        description={
                          <div>
                            <div>无上游依赖</div>
                            <div className="empty-detail-copy">
                              添加依赖后，上游任务在同一业务日期成功前，本任务会被标记为「已跳过」而非失败。
                            </div>
                          </div>
                        }
                      />
                    ) : (
                      <Table<TaskDependency>
                        size="small"
                        rowKey="id"
                        pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 条依赖` }}
                        dataSource={dependencies}
                        columns={[
                          {
                            title: '上游任务',
                            key: 'upstream',
                            render: (_, record) => taskNameOf(record.upstreamTaskId)
                          },
                          {
                            title: '操作',
                            key: 'action',
                            width: 90,
                            render: (_, record) => (
                              <Popconfirm
                                title="移除该依赖？"
                                onConfirm={() => void handleRemoveDependency(record.upstreamTaskId)}
                              >
                                <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                                  移除
                                </Button>
                              </Popconfirm>
                            )
                          }
                        ]}
                      />
                    )}
                  </>
                )
              }
            ]}
          />
        )}
      </Spin>
    </Drawer>
  )
}
