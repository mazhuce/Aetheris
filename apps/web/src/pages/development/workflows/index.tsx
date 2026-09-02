import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Empty, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ApartmentOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import RowActions from '@/components/RowActions'
import {
  addDependency,
  listDependencies,
  listTasks,
  removeDependency,
  type TaskDependency,
  type TaskView
} from '@/api/workflow'
import { canManagePlatform } from '@/utils/access'

/** DAG 节点：任务 + 拓扑层级 + 画布坐标。 */
interface DagNode {
  task: TaskView
  depth: number
  x: number
  y: number
}

/** DAG 边：上游 → 下游。 */
interface DagEdge {
  from: number
  to: number
}

const NODE_WIDTH = 180
const NODE_HEIGHT = 56
const LAYER_GAP = 100
const ROW_GAP = 18

/** 依据依赖关系迭代计算每个任务的最深层级（最长上游路径），环依赖返回 null。 */
function computeDepths(tasks: TaskView[], deps: Map<number, number[]>): Map<number, number> | null {
  const depth = new Map<number, number>()
  tasks.forEach((view) => depth.set(view.task.id, 0))
  // 迭代松弛：边数上限内未收敛即存在环。
  const maxRounds = tasks.length + 1
  for (let round = 0; round < maxRounds; round++) {
    let changed = false
    for (const [downstream, upstreams] of deps) {
      for (const upstream of upstreams) {
        if (!depth.has(upstream)) continue
        const next = depth.get(upstream)! + 1
        if (next > (depth.get(downstream) ?? 0)) {
          depth.set(downstream, next)
          changed = true
        }
      }
    }
    if (!changed) return depth
  }
  return null
}

export default function WorkflowGraphPage() {
  const { message } = App.useApp()
  const canManage = canManagePlatform()
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [deps, setDeps] = useState<Map<number, number[]>>(new Map())
  const [loading, setLoading] = useState(false)
  const [selectedId, setSelectedId] = useState<number | null>(null)
  const [selectedDeps, setSelectedDeps] = useState<TaskDependency[]>([])
  const [depsLoading, setDepsLoading] = useState(false)
  const [addUpstream, setAddUpstream] = useState<number | undefined>(undefined)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const views = await listTasks()
      setTasks(views)
      // 逐任务拉取依赖，构建全局邻接表（上游列表）。
      const map = new Map<number, number[]>()
      await Promise.all(
        views.map(async (view) => {
          try {
            const list = await listDependencies(view.task.id)
            map.set(
              view.task.id,
              list.map((dep) => dep.upstreamTaskId)
            )
          } catch {
            map.set(view.task.id, [])
          }
        })
      )
      setDeps(map)
    } catch {
      message.error('任务依赖加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const loadSelectedDeps = useCallback(
    async (id: number) => {
      setDepsLoading(true)
      try {
        setSelectedDeps(await listDependencies(id))
      } catch {
        message.error('依赖详情加载失败')
      } finally {
        setDepsLoading(false)
      }
    },
    [message]
  )

  useEffect(() => {
    if (selectedId != null) void loadSelectedDeps(selectedId)
    else setSelectedDeps([])
  }, [selectedId, loadSelectedDeps])

  /** 布局：拓扑分层，层内纵向排布。环依赖时整体退化展示为单列。 */
  const { nodes, edges, canvasWidth, canvasHeight, hasCycle } = useMemo(() => {
    if (!tasks.length) {
      return { nodes: [] as DagNode[], edges: [] as DagEdge[], canvasWidth: 0, canvasHeight: 0, hasCycle: false }
    }
    const depths = computeDepths(tasks, deps) ?? new Map(tasks.map((view) => [view.task.id, 0]))
    const cycled = computeDepths(tasks, deps) === null
    const layers = new Map<number, TaskView[]>()
    tasks.forEach((view) => {
      const d = depths.get(view.task.id) ?? 0
      if (!layers.has(d)) layers.set(d, [])
      layers.get(d)!.push(view)
    })
    const nodeList: DagNode[] = []
    layers.forEach((layerViews, depth) => {
      layerViews.forEach((view, index) => {
        nodeList.push({
          task: view,
          depth,
          x: depth * (NODE_WIDTH + LAYER_GAP),
          y: index * (NODE_HEIGHT + ROW_GAP)
        })
      })
    })
    const edgeList: DagEdge[] = []
    deps.forEach((upstreams, downstream) => {
      upstreams.forEach((upstream) => edgeList.push({ from: upstream, to: downstream }))
    })
    const maxX = Math.max(...nodeList.map((node) => node.x)) + NODE_WIDTH
    const maxY = Math.max(...nodeList.map((node) => node.y)) + NODE_HEIGHT
    return { nodes: nodeList, edges: edgeList, canvasWidth: maxX, canvasHeight: maxY, hasCycle: cycled }
  }, [tasks, deps])

  const stats = useMemo(
    () => ({
      total: tasks.length,
      edgeCount: edges.length,
      depth: nodes.length ? Math.max(...nodes.map((node) => node.depth)) + 1 : 0
    }),
    [tasks, edges, nodes]
  )

  const nodeById = useMemo(() => new Map(nodes.map((node) => [node.task.task.id, node])), [nodes])

  const selectedTask = useMemo(() => tasks.find((view) => view.task.id === selectedId) ?? null, [tasks, selectedId])

  /** 除自身及其下游外均可选为上游，避免直接成环（后端会拒绝成环依赖）。 */
  const candidateUpstreams = useMemo(() => {
    if (!selectedTask) return []
    // 求下游闭包：候选上游不能在自身下游集合里。
    const downstreamClosure = new Set<number>([selectedTask.task.id])
    let growing = true
    while (growing) {
      growing = false
      deps.forEach((upstreams, downstream) => {
        if (downstreamClosure.has(downstream)) {
          upstreams.forEach((upstream) => {
            if (!downstreamClosure.has(upstream)) {
              downstreamClosure.add(upstream)
              growing = true
            }
          })
        }
      })
    }
    return tasks
      .filter((view) => !downstreamClosure.has(view.task.id))
      .map((view) => ({ value: view.task.id, label: view.task.name }))
  }, [tasks, deps, selectedTask])

  const handleAddDep = async () => {
    if (selectedId == null || addUpstream == null) return
    try {
      await addDependency(selectedId, addUpstream)
      message.success('依赖已添加')
      setAddUpstream(undefined)
      await refresh()
      await loadSelectedDeps(selectedId)
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '添加失败')
    }
  }

  const handleRemoveDep = async (upstreamTaskId: number) => {
    if (selectedId == null) return
    try {
      await removeDependency(selectedId, upstreamTaskId)
      message.success('依赖已移除')
      await refresh()
      await loadSelectedDeps(selectedId)
    } catch {
      message.error('移除失败')
    }
  }

  const depColumns: TableColumnsType<TaskDependency> = [
    {
      title: '上游任务',
      dataIndex: 'upstreamTaskId',
      key: 'upstreamTaskId',
      render: (v: number) => tasks.find((view) => view.task.id === v)?.task.name ?? `任务 #${v}`
    },
    {
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      render: (_, record) => (
        <RowActions
          items={[
            {
              key: 'remove',
              label: '移除',
              danger: true,
              onClick: () => void handleRemoveDep(record.upstreamTaskId)
            }
          ]}
        />
      )
    }
  ]

  return (
    <>
      <PageHeader description="以可视化方式编排任务依赖，构建端到端数据加工流水线，依赖就绪后自动联动调度。" />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="任务节点" value={String(stats.total)} icon={<ApartmentOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="依赖连线" value={String(stats.edgeCount)} icon={<ApartmentOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="流水线深度" value={String(stats.depth)} icon={<ApartmentOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <Typography.Text type="secondary">点击画布中的任务节点，在下方维护其上游依赖</Typography.Text>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="任务依赖图">
        {hasCycle && (
          <div className="dep-cycle-warning">
            <Tag color="error">检测到循环依赖</Tag>
            <Typography.Text type="warning">图中存在环，无法分层展示，请检查并解除循环依赖。</Typography.Text>
          </div>
        )}
        {nodes.length ? (
          <div className="dag-canvas" style={{ width: canvasWidth, height: canvasHeight }}>
            <svg className="dag-edges" width={canvasWidth} height={canvasHeight}>
              {edges.map((edge) => {
                const from = nodeById.get(edge.from)
                const to = nodeById.get(edge.to)
                if (!from || !to) return null
                const x1 = from.x + NODE_WIDTH
                const y1 = from.y + NODE_HEIGHT / 2
                const x2 = to.x
                const y2 = to.y + NODE_HEIGHT / 2
                const mid = (x1 + x2) / 2
                const active = selectedId === edge.to
                return (
                  <path
                    key={`${edge.from}-${edge.to}`}
                    d={`M ${x1} ${y1} C ${mid} ${y1}, ${mid} ${y2}, ${x2} ${y2}`}
                    className={active ? 'dag-edge active' : 'dag-edge'}
                    markerEnd="url(#dag-arrow)"
                  />
                )
              })}
              <defs>
                <marker id="dag-arrow" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                  <path d="M 0 0 L 8 4 L 0 8 Z" className="dag-arrow" />
                </marker>
              </defs>
            </svg>
            {nodes.map((node) => {
              const selected = selectedId === node.task.task.id
              return (
                <button
                  key={node.task.task.id}
                  type="button"
                  className={selected ? 'dag-node selected' : 'dag-node'}
                  style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                  onClick={() => setSelectedId(selected ? null : node.task.task.id)}
                  title={node.task.task.description || node.task.task.name}
                >
                  <span className="dag-node-name">{node.task.task.name}</span>
                  <span className="dag-node-meta">
                    {node.task.task.targetTable ? node.task.task.targetTable : node.task.task.taskType?.toUpperCase() ?? 'SQL'}
                    {deps.get(node.task.task.id)?.length ? ` · ${deps.get(node.task.task.id)!.length} 上游` : ''}
                  </span>
                </button>
              )
            })}
          </div>
        ) : (
          <Empty description={loading ? '依赖关系加载中…' : '暂无任务，请先在任务调度中创建加工任务'} />
        )}
      </DataSurface>
      <DataSurface label={selectedTask ? `上游依赖 · ${selectedTask.task.name}` : '上游依赖'}>
        {selectedTask ? (
          <>
            <Table<TaskDependency>
              size="small"
              rowKey="id"
              columns={depColumns}
              dataSource={selectedDeps}
              loading={depsLoading}
              pagination={false}
              locale={{ emptyText: '暂无上游依赖，该任务为流水线起点' }}
            />
            {canManage && (
              <div className="dep-add-bar">
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择任务添加为上游依赖"
                  value={addUpstream}
                  onChange={setAddUpstream}
                  options={candidateUpstreams}
                  className="dep-add-select"
                  allowClear
                />
                <Button type="primary" disabled={addUpstream == null} onClick={() => void handleAddDep()}>
                  添加依赖
                </Button>
              </div>
            )}
          </>
        ) : (
          <Empty description="在上方依赖图中点击任务节点后，可在此维护其上游依赖" />
        )}
      </DataSurface>
    </>
  )
}
