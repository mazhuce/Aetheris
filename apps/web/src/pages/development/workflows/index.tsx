import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Alert, App, Button, Col, Empty, Row, Select, Table, Tag, Typography } from 'antd'
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
import { useResourceActions } from '@/hooks/useResourceActions'
import { checkedDependencies, DEPENDENCY_CONCURRENCY, DependencyRequestQueue, dependencyFailure } from './dependencyRequests'

interface Selection { id: number | null; epoch: number }
type DependencyRead = { status: 'loading' } | { status: 'ready'; rows: TaskDependency[] } | { status: 'error'; error: string }
interface DetailRead { owner: Selection; value: DependencyRead }

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

const NODE_WIDTH = 150
const NODE_HEIGHT = 44
const LAYER_GAP = 56
const ROW_GAP = 10

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
  const resources = useMemo(() => tasks.map((view) => view.task), [tasks])
  const permissions = useResourceActions('workflow_task', resources)
  const [graphReads, setGraphReads] = useState<Map<number, DependencyRead>>(new Map())
  const [loading, setLoading] = useState(false)
  const [graphError, setGraphError] = useState<string | null>(null)
  const selectionRef = useRef<Selection>({ id: null, epoch: 0 })
  const [selection, setSelection] = useState(selectionRef.current)
  const selectedId = selection.id
  const canUpdate = selectedId != null && (canManage || permissions.can('update', selectedId))
  const [detail, setDetail] = useState<DetailRead | null>(null)
  const [addUpstream, setAddUpstream] = useState<number | undefined>(undefined)
  const [mutationPending, setMutationPending] = useState(false)
  const mutationRef = useRef<symbol | null>(null)
  const live = useRef(true)
  const graphEpoch = useRef(0)
  const detailEpoch = useRef(0)
  const graphController = useRef<AbortController | null>(null)
  const detailController = useRef<AbortController | null>(null)
  const queue = useMemo(() => new DependencyRequestQueue(), [])
  const currentSelection = useCallback((owner: Selection) => live.current && owner.id === selectionRef.current.id
    && owner.epoch === selectionRef.current.epoch, [])

  const changeSelection = useCallback((id: number | null) => {
    detailController.current?.abort()
    detailEpoch.current++
    const owner = { id, epoch: selectionRef.current.epoch + 1 }
    selectionRef.current = owner
    setSelection(owner)
    setDetail(id === null ? null : { owner, value: { status: 'loading' } })
    setAddUpstream(undefined)
    mutationRef.current = null
    setMutationPending(false)
  }, [])

  const loadSelectedDeps = useCallback(async (owner: Selection) => {
    if (owner.id === null || !currentSelection(owner)) return
    detailController.current?.abort()
    const controller = new AbortController()
    detailController.current = controller
    const epoch = ++detailEpoch.current
    setDetail({ owner, value: { status: 'loading' } })
    try {
      const rows = checkedDependencies(owner.id, await queue.run(() => listDependencies(owner.id!, controller.signal), controller.signal))
      if (currentSelection(owner) && epoch === detailEpoch.current && !controller.signal.aborted) {
        setDetail({ owner, value: { status: 'ready', rows } })
      }
    } catch (error) {
      if (currentSelection(owner) && epoch === detailEpoch.current && !controller.signal.aborted) {
        setDetail({ owner, value: { status: 'error', error: dependencyFailure(error) } })
      }
    }
  }, [currentSelection, queue])

  const loadGraphReads = useCallback(async (views: TaskView[], controller: AbortController, epoch: number) => {
    let cursor = 0
    const active = () => live.current && epoch === graphEpoch.current && !controller.signal.aborted
    await Promise.all(Array.from({ length: Math.min(DEPENDENCY_CONCURRENCY, views.length) }, async () => {
      while (active()) {
        const view = views[cursor++]
        if (!view) return
        const id = view.task.id
        let value: DependencyRead
        try {
          const rows = checkedDependencies(id, await queue.run(() => listDependencies(id, controller.signal), controller.signal))
          value = { status: 'ready', rows }
        } catch (error) {
          if (!active()) return
          value = { status: 'error', error: dependencyFailure(error) }
        }
        if (active()) setGraphReads((previous) => new Map(previous).set(id, value))
      }
    }))
  }, [queue])

  const refresh = useCallback(async () => {
    graphController.current?.abort()
    const controller = new AbortController()
    graphController.current = controller
    const epoch = ++graphEpoch.current
    const active = () => live.current && epoch === graphEpoch.current && !controller.signal.aborted
    setLoading(true)
    setGraphError(null)
    try {
      const views = await listTasks(controller.signal)
      if (!active()) return
      setTasks(views)
      if (selectionRef.current.id !== null && !views.some((view) => view.task.id === selectionRef.current.id)) changeSelection(null)
      setGraphReads(new Map(views.map((view) => [view.task.id, { status: 'loading' } as DependencyRead])))
      await loadGraphReads(views, controller, epoch)
    } catch {
      if (active()) setGraphError('任务列表读取失败，无法确认完整依赖图，请重试')
    } finally {
      if (active()) setLoading(false)
    }
  }, [changeSelection, loadGraphReads])

  const refreshInBackground = useCallback(() => {
    const pending = refresh()
    const epoch = graphEpoch.current
    pending.catch(() => {
      if (live.current && epoch === graphEpoch.current) {
        setGraphError('依赖图读取中断，请重试')
        setLoading(false)
      }
    })
  }, [refresh])

  const stopReads = useCallback(() => {
    live.current = false
    graphEpoch.current++
    detailEpoch.current++
    graphController.current?.abort()
    detailController.current?.abort()
  }, [])

  useEffect(() => {
    live.current = true
    refreshInBackground()
    return stopReads
  }, [refreshInBackground, stopReads])

  useEffect(() => {
    if (selection.id !== null) {
      const pending = loadSelectedDeps(selection)
      const epoch = detailEpoch.current
      pending.catch((error) => {
        if (currentSelection(selection) && epoch === detailEpoch.current) {
          setDetail({ owner: selection, value: { status: 'error', error: dependencyFailure(error) } })
        }
      })
    }
    return () => { detailController.current?.abort() }
  }, [selection, loadSelectedDeps, currentSelection])

  const retryUnknown = async () => {
    const views = tasks.filter((view) => graphReads.get(view.task.id)?.status !== 'ready')
    graphController.current?.abort()
    const controller = new AbortController()
    graphController.current = controller
    const epoch = ++graphEpoch.current
    setLoading(true)
    setGraphReads((previous) => {
      const next = new Map(previous)
      views.forEach((view) => next.set(view.task.id, { status: 'loading' }))
      return next
    })
    await loadGraphReads(views, controller, epoch)
    if (live.current && epoch === graphEpoch.current && !controller.signal.aborted) setLoading(false)
  }

  const cancelGraphRead = () => {
    graphEpoch.current++
    graphController.current?.abort()
    setLoading(false)
    if (!tasks.length) setGraphError('任务列表读取已取消，请重试')
    setGraphReads((previous) => {
      const next = new Map(previous)
      for (const [id, value] of next) if (value.status === 'loading') next.set(id, { status: 'error', error: '加载已取消，请重试' })
      return next
    })
  }

  const deps = useMemo(() => new Map(Array.from(graphReads).flatMap(([id, value]) =>
    value.status === 'ready' ? [[id, value.rows.map((row) => row.upstreamTaskId)] as const] : [])), [graphReads])
  const unknownCount = tasks.filter((view) => graphReads.get(view.task.id)?.status !== 'ready').length
  const detailValue = detail && detail.owner.id === selection.id && detail.owner.epoch === selection.epoch ? detail.value : null
  const selectedDeps = useMemo(() => detailValue?.status === 'ready' ? detailValue.rows : [], [detailValue])
  const depsLoading = detailValue?.status === 'loading'

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
    // 计算每一层的 y 起点：层 d 的 y = d * (LAYER_GAP + NODE_HEIGHT) + index * (ROW_GAP + NODE_HEIGHT)
    // —— 把"层间间距"和"层内行距"叠加，让不同层节点在视觉上分行。
    const sortedDepths = Array.from(layers.keys()).sort((a, b) => a - b)
    const layerYOffset = new Map<number, number>()
    sortedDepths.forEach((d, layerIndex) => {
      layerYOffset.set(d, layerIndex * (NODE_HEIGHT + LAYER_GAP))
    })
    const nodeList: DagNode[] = []
    layers.forEach((layerViews, depth) => {
      const yBase = layerYOffset.get(depth) ?? 0
      layerViews.forEach((view, index) => {
        nodeList.push({
          task: view,
          depth,
          x: depth * (NODE_WIDTH + LAYER_GAP),
          y: yBase + index * (NODE_HEIGHT + ROW_GAP)
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
    // 由已知上游边向下游扩张；后端仍校验不完整图及并发变更中的环。
    const downstreamClosure = new Set<number>([selectedTask.task.id])
    const existingUpstreams = new Set([
      ...(deps.get(selectedTask.task.id) ?? []), ...selectedDeps.map((row) => row.upstreamTaskId)
    ])
    let growing = true
    while (growing) {
      growing = false
      deps.forEach((upstreams, downstream) => {
        if (!downstreamClosure.has(downstream) && upstreams.some((upstream) => downstreamClosure.has(upstream))) {
          downstreamClosure.add(downstream)
          growing = true
        }
      })
    }
    return tasks
      .filter((view) => !downstreamClosure.has(view.task.id) && !existingUpstreams.has(view.task.id))
      .map((view) => ({ value: view.task.id, label: view.task.name }))
  }, [tasks, deps, selectedTask, selectedDeps])

  const handleAddDep = async () => {
    const owner = selection
    if (owner.id === null || addUpstream == null || !currentSelection(owner)
        || detailValue?.status !== 'ready' || !canUpdate || mutationRef.current) return
    const token = Symbol('add-dependency')
    mutationRef.current = token
    setMutationPending(true)
    try {
      await addDependency(owner.id, addUpstream)
      if (!live.current) return
      refreshInBackground()
      if (!currentSelection(owner)) return
      message.success('依赖已添加')
      setAddUpstream(undefined)
      await loadSelectedDeps(owner)
    } catch (err) {
      if (!currentSelection(owner)) return
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '添加失败')
    } finally {
      if (currentSelection(owner) && mutationRef.current === token) {
        mutationRef.current = null
        setMutationPending(false)
      }
    }
  }

  const handleRemoveDep = async (record: TaskDependency, owner: Selection) => {
    if (owner.id === null || record.taskId !== owner.id || !currentSelection(owner)
        || detailValue?.status !== 'ready' || !canUpdate || mutationRef.current) return
    const token = Symbol('remove-dependency')
    mutationRef.current = token
    setMutationPending(true)
    try {
      await removeDependency(owner.id, record.upstreamTaskId)
      if (!live.current) return
      refreshInBackground()
      if (!currentSelection(owner)) return
      message.success('依赖已移除')
      await loadSelectedDeps(owner)
    } catch {
      if (currentSelection(owner)) message.error('移除失败，请核对当前依赖后重试')
    } finally {
      if (currentSelection(owner) && mutationRef.current === token) {
        mutationRef.current = null
        setMutationPending(false)
      }
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
          items={canUpdate && detailValue?.status === 'ready' && record.taskId === selectedId ? [
            {
              key: 'remove',
              label: '移除',
              danger: true,
              disabled: mutationPending,
              onClick: () => void handleRemoveDep(record, selection)
            }
          ] : []}
        />
      )
    }
  ]

  return (
    <div className="workflow-graph-page">
      <PageHeader description="以可视化方式编排任务依赖，构建端到端数据加工流水线，依赖就绪后自动联动调度。" />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="任务节点" value={String(stats.total)} icon={<ApartmentOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="依赖连线" value={`${stats.edgeCount}${unknownCount || graphError ? '（已确认）' : ''}`} icon={<ApartmentOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="流水线深度" value={unknownCount || graphError ? '未知' : String(stats.depth)} icon={<ApartmentOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <Typography.Text type="secondary">点击画布中的任务节点，在下方维护其上游依赖</Typography.Text>
          <Button icon={<ReloadOutlined />} onClick={refreshInBackground}>刷新</Button>
          {loading && <Button onClick={cancelGraphRead}>取消加载</Button>}
        </div>
      </PageToolbar>
      <DataSurface label="任务依赖图" className="dag-surface">
        {graphError && <Alert type="error" showIcon message={graphError} />}
        {unknownCount > 0 && <Alert type="warning" showIcon
          message={`${unknownCount} 个任务的依赖未知，图中只显示已确认的连线`}
          action={<Button disabled={loading} onClick={() => void retryUnknown()}>重试未知依赖</Button>} />}
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
              const read = graphReads.get(node.task.task.id)
              return (
                <button
                  key={node.task.task.id}
                  type="button"
                  className={selected ? 'dag-node selected' : 'dag-node'}
                  style={{ left: node.x, top: node.y, width: NODE_WIDTH, height: NODE_HEIGHT }}
                  onClick={() => changeSelection(selected ? null : node.task.task.id)}
                  title={node.task.task.description || node.task.task.name}
                >
                  <span className="dag-node-name">{node.task.task.name}</span>
                  <span className="dag-node-meta" title={read?.status === 'error' ? read.error : undefined}>
                    {node.task.task.targetTable ? node.task.task.targetTable : node.task.task.taskType?.toUpperCase() ?? 'SQL'}
                    {graphReads.get(node.task.task.id)?.status === 'ready'
                      ? ` · ${deps.get(node.task.task.id)!.length} 上游`
                      : ' · 依赖未知'}
                  </span>
                </button>
              )
            })}
          </div>
        ) : (
          <Empty description={loading ? '依赖关系加载中…' : graphError ? '任务列表未知' : '暂无任务，请先在任务调度中创建加工任务'} />
        )}
      </DataSurface>
      <DataSurface label={selectedTask ? `上游依赖 · ${selectedTask.task.name}` : '上游依赖'}>
        {selectedTask ? (
          <>
            {detailValue?.status === 'error' && <Alert type="warning" showIcon message="依赖详情未知"
              description={detailValue.error}
              action={<Button onClick={() => void loadSelectedDeps(selection)}>重试依赖详情</Button>} />}
            <Table<TaskDependency>
              size="small"
              rowKey="id"
              columns={depColumns}
              dataSource={selectedDeps}
              loading={depsLoading}
              pagination={detailValue?.status === 'ready' ? { pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 条依赖` } : false}
              locale={{ emptyText: detailValue?.status === 'ready' ? '暂无上游依赖，该任务为流水线起点'
                : depsLoading ? '依赖详情加载中…' : '尚未确认上游依赖' }}
            />
            {canUpdate && (
              <div className="dep-add-bar">
                <Select
                  showSearch
                  optionFilterProp="label"
                  placeholder="选择任务添加为上游依赖"
                  disabled={detailValue?.status !== 'ready' || mutationPending}
                  value={addUpstream}
                  onChange={setAddUpstream}
                  options={candidateUpstreams}
                  className="dep-add-select"
                  allowClear
                />
                <Button type="primary" disabled={addUpstream == null || detailValue?.status !== 'ready' || mutationPending} onClick={() => void handleAddDep()}>
                  添加依赖
                </Button>
              </div>
            )}
          </>
        ) : (
          <Empty description="在上方依赖图中点击任务节点后，可在此维护其上游依赖" />
        )}
      </DataSurface>
    </div>
  )
}
