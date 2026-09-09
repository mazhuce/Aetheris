import { useEffect, useMemo, useState } from 'react'
import { Alert, App, Button, Card, Col, DatePicker, Descriptions, Drawer, Form, Input, Modal, Popconfirm, Row, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import { CaretRightOutlined, ReloadOutlined, StopOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import {
  cancelPipeline, cancelTaskInstance, getPipelineRun, getSparkHealth, listAllInstances, listPipelineRuns, listSparkArtifacts,
  listSparkEnvironments, listSparkJobs, listTasks, resolveTaskInstance, runTask, startPipeline,
  retryPipeline,
  type ExecutionResolutionInput,
  type ManagedArtifact, type PipelineDetail, type PipelineNodeRun, type PipelineRun, type SparkEnvironment, type SparkJobDefinition,
  type SparkRunnerHealth, type TaskInstance, type TaskView
} from '@/api/workflow'
import { pipelineTargetOptions, sparkTaskOptions } from './taskOptions'
import { canCancelPipeline, canResolveUnknownInstance, canRetryPipelineNode } from './pipelineActions'

export default function SparkDevelopmentPage() {
  const { message } = App.useApp()
  const [health, setHealth] = useState<SparkRunnerHealth | null>(null)
  const [environments, setEnvironments] = useState<SparkEnvironment[]>([])
  const [artifacts, setArtifacts] = useState<ManagedArtifact[]>([])
  const [jobs, setJobs] = useState<SparkJobDefinition[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [instances, setInstances] = useState<TaskInstance[]>([])
  const [pipelines, setPipelines] = useState<PipelineRun[]>([])
  const [pipelineDetail, setPipelineDetail] = useState<PipelineDetail | null>(null)
  const [detailLoading, setDetailLoading] = useState(false)
  const [sparkTaskId, setSparkTaskId] = useState<number>()
  const [pipelineTargetId, setPipelineTargetId] = useState<number>()
  const [bizDate, setBizDate] = useState<Dayjs>(dayjs().subtract(1, 'day'))
  const [busy, setBusy] = useState(false)
  const [pipelineAction, setPipelineAction] = useState<string>()
  const [resolutionTarget, setResolutionTarget] = useState<{ instanceId: number; label: string; pipeline: boolean }>()
  const [resolutionForm] = Form.useForm<ExecutionResolutionInput>()

  const refresh = async () => {
    const settled = await Promise.allSettled([
      getSparkHealth(), listSparkEnvironments(), listSparkArtifacts(), listSparkJobs(),
      listTasks(), listAllInstances(undefined, 100), listPipelineRuns()
    ])
    if (settled[0].status === 'fulfilled') setHealth(settled[0].value)
    else setHealth({ status: 'DOWN', activeJobs: 0, maxConcurrency: 0 })
    if (settled[1].status === 'fulfilled') setEnvironments(settled[1].value)
    if (settled[2].status === 'fulfilled') setArtifacts(settled[2].value)
    if (settled[3].status === 'fulfilled') setJobs(settled[3].value)
    if (settled[4].status === 'fulfilled') setTasks(settled[4].value)
    if (settled[5].status === 'fulfilled') setInstances(settled[5].value)
    if (settled[6].status === 'fulfilled') setPipelines(settled[6].value)
  }

  useEffect(() => { void refresh(); const timer = window.setInterval(() => void refresh(), 5000); return () => window.clearInterval(timer) }, [])
  const individualTaskOptions = useMemo(() => sparkTaskOptions(tasks), [tasks])
  const pipelineOptions = useMemo(() => pipelineTargetOptions(tasks), [tasks])
  const sparkTaskIds = useMemo(() => new Set(individualTaskOptions.map((option) => option.value)), [individualTaskOptions])
  const sparkInstances = instances.filter((item) => sparkTaskIds.has(item.taskId))

  const execute = async (pipeline: boolean) => {
    const selectedId = pipeline ? pipelineTargetId : sparkTaskId
    if (!selectedId) return message.warning(pipeline ? '请选择流水线目标任务' : '请选择 Spark 任务')
    setBusy(true)
    try {
      if (pipeline) await startPipeline(selectedId, bizDate.format('YYYY-MM-DD'))
      else await runTask(selectedId, bizDate.format('YYYY-MM-DD'))
      message.success(pipeline ? '流水线已开始' : 'Spark 任务已提交')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '提交失败')
    } finally { setBusy(false) }
  }

  const cancel = async (id: number) => {
    try { await cancelTaskInstance(id); message.success('取消请求已确认'); await refresh() }
    catch (error) { message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '取消失败') }
  }

  const showPipeline = async (id: number) => {
    setDetailLoading(true)
    try { setPipelineDetail(await getPipelineRun(id)) }
    catch { message.error('流水线详情加载失败') }
    finally { setDetailLoading(false) }
  }

  const cancelPipelineRun = async (id: number) => {
    const action = `cancel-${id}`
    setPipelineAction(action)
    try {
      const result = await cancelPipeline(id)
      message.success(result.cancellation_requested ? '远端取消请求已提交，等待确认' : '流水线已取消')
      if (pipelineDetail?.id === id) setPipelineDetail(await getPipelineRun(id))
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '流水线取消失败')
    } finally { setPipelineAction(undefined) }
  }

  const retryFromNode = async (node: PipelineNodeRun) => {
    if (!pipelineDetail) return
    const action = `retry-${pipelineDetail.id}-${node.task_id}`
    setPipelineAction(action)
    try {
      const result = await retryPipeline(pipelineDetail.id, node.task_id)
      message.success(`已创建续跑流水线 #${result.id}`)
      setPipelineDetail(null)
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '断点重跑失败')
    } finally { setPipelineAction(undefined) }
  }

  const openResolution = (instanceId: number, label: string, pipeline = false) => {
    resolutionForm.resetFields()
    setResolutionTarget({ instanceId, label, pipeline })
  }

  const submitResolution = async () => {
    if (!resolutionTarget) return
    let values: ExecutionResolutionInput
    try { values = await resolutionForm.validateFields() }
    catch { return }
    const action = `resolve-${resolutionTarget.instanceId}`
    setPipelineAction(action)
    try {
      await resolveTaskInstance(resolutionTarget.instanceId, values)
      message.success('核对结果已登记；关联流水线将按该结果自动恢复或终止')
      if (resolutionTarget.pipeline) setPipelineDetail(null)
      setResolutionTarget(undefined)
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '结果认领失败')
    } finally { setPipelineAction(undefined) }
  }

  const statusTag = (status: string) => {
    const color = status === 'succeeded' || status === 'success' ? 'success'
      : status === 'failed' ? 'error'
        : status === 'running' ? 'processing'
          : status === 'waived' || status === 'skipped' || status === 'unknown' ? 'warning' : 'default'
    return <Tag color={color}>{status}</Tag>
  }

  return <div className="page-stack">
    <PageHeader title="Spark 开发" description="受管 PySpark 制品、固定资源边界、可恢复执行与混合 DAG。当前后端是 WSL 本机开发环境。" />
    <Row gutter={12}>
      <Col span={8}><Card size="small"><Descriptions column={1} size="small" title="运行器">
        <Descriptions.Item label="状态"><Tag color={health?.status === 'UP' ? 'success' : 'error'}>{health?.status || 'LOADING'}</Tag></Descriptions.Item>
        <Descriptions.Item label="活动 / 上限">{health?.activeJobs ?? '-'} / {health?.maxConcurrency ?? '-'}</Descriptions.Item>
      </Descriptions></Card></Col>
      <Col span={16}><Card size="small" title="提交">
        <Space wrap><Select value={sparkTaskId} onChange={setSparkTaskId} placeholder="选择单个 Spark 任务" style={{ width: 260 }}
          options={individualTaskOptions} />
          <DatePicker value={bizDate} onChange={(value) => value && setBizDate(value)} allowClear={false} />
          <Button type="primary" icon={<CaretRightOutlined />} loading={busy} onClick={() => void execute(false)}>运行任务</Button>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </Space>
        <Space wrap style={{ marginTop: 12 }}>
          <Select value={pipelineTargetId} onChange={setPipelineTargetId} placeholder="选择 SQL / Spark / 质量流水线终点" style={{ width: 360 }}
            showSearch optionFilterProp="label" options={pipelineOptions} />
          <Button loading={busy} onClick={() => void execute(true)}>运行完整依赖链</Button>
        </Space>
      </Card></Col>
    </Row>
    <Tabs items={[
      { key: 'runs', label: '运行记录', children: <DataSurface label="Spark 运行记录"><Table rowKey="id" size="small" dataSource={sparkInstances} pagination={{ pageSize: 10 }} columns={[
        { title: '实例', dataIndex: 'id', width: 80 }, { title: '任务', dataIndex: 'taskId', width: 80 },
        { title: '业务日期', dataIndex: 'bizDate' }, { title: '状态', dataIndex: 'status', render: (v: string) => <Tag>{v}</Tag> },
        { title: '耗时(ms)', dataIndex: 'durationMs' }, { title: '操作', width: 150, render: (_, row: TaskInstance) => <Space size={6}>
          {['submitting', 'running', 'cancel_requested'].includes(row.status) && <Button danger size="small" icon={<StopOutlined />} onClick={() => void cancel(row.id)}>取消</Button>}
          {canResolveUnknownInstance(row.status, row.id) && <Button size="small" onClick={() => openResolution(row.id, `Spark 实例 #${row.id}`)}>确认结果</Button>}
        </Space> }
      ]} /></DataSurface> },
      { key: 'jobs', label: '作业定义', children: <Table rowKey="id" size="small" dataSource={jobs} pagination={{ pageSize: 10 }} columns={[
        { title: '名称', dataIndex: 'name' }, { title: '版本', dataIndex: 'version' }, { title: '制品', dataIndex: 'artifactKey' },
        { title: '输入', dataIndex: 'inputTables', render: (v: string[]) => v?.join(', ') }, { title: '输出', dataIndex: 'outputUri' },
        { title: '资源', render: (_, v: SparkJobDefinition) => `${v.driverCores}C / ${v.driverMemoryMb}MB` }, { title: '状态', dataIndex: 'status' }
      ]} /> },
      { key: 'artifacts', label: '制品与环境', children: <Space direction="vertical" className="full-width-control">
        <Typography.Text type="secondary">运行器只接受仓库白名单中的 PySpark 文件，并在提交前核对 SHA-256。</Typography.Text>
        <Table rowKey="id" size="small" dataSource={artifacts} pagination={false} columns={[
          { title: '制品', dataIndex: 'name' }, { title: '版本', dataIndex: 'version' }, { title: '文件', dataIndex: 'artifactKey' },
          { title: 'SHA-256', dataIndex: 'sha256', ellipsis: true }, { title: '状态', dataIndex: 'status' }
        ]} />
        <Table rowKey="id" size="small" dataSource={environments} pagination={false} columns={[
          { title: '环境', dataIndex: 'name' }, { title: 'Spark', dataIndex: 'sparkVersion' }, { title: 'Master', dataIndex: 'master' },
          { title: '上限', render: (_, v: SparkEnvironment) => `${v.maxCores}C / ${v.maxMemoryMb}MB / 并发${v.maxConcurrency}` }, { title: '状态', dataIndex: 'status' }
        ]} />
      </Space> },
      { key: 'pipelines', label: '混合流水线', children: <Table rowKey="id" size="small" dataSource={pipelines} pagination={{ pageSize: 10 }} columns={[
        { title: '运行', dataIndex: 'id' }, { title: '目标', dataIndex: 'target_task_name' }, { title: '业务日期', dataIndex: 'biz_date' },
        { title: '状态', dataIndex: 'status', render: statusTag }, { title: '触发人', dataIndex: 'triggered_by' },
        { title: '错误', dataIndex: 'error_message', ellipsis: true },
        { title: '操作', width: 190, render: (_, row: PipelineRun) => <Space size={6}>
          <Button size="small" loading={detailLoading} onClick={() => void showPipeline(row.id)}>节点详情</Button>
          {canCancelPipeline(row.status) && <Popconfirm title="确认取消此流水线？" description="仅能可靠取消排队任务或正在运行的 Spark 节点。"
            onConfirm={() => void cancelPipelineRun(row.id)} okText="确认取消" cancelText="返回">
            <Button danger size="small" icon={<StopOutlined />} loading={pipelineAction === `cancel-${row.id}`}>取消</Button>
          </Popconfirm>}
        </Space> }
      ]} /> }
    ]} />
    <Drawer title={pipelineDetail ? `流水线 #${pipelineDetail.id}` : '流水线详情'} width={760}
      open={pipelineDetail !== null} onClose={() => setPipelineDetail(null)}>
      {pipelineDetail && <Space direction="vertical" size="middle" className="full-width-control">
        <Descriptions size="small" column={3} bordered>
          <Descriptions.Item label="业务日期">{pipelineDetail.biz_date}</Descriptions.Item>
          <Descriptions.Item label="状态">{statusTag(pipelineDetail.status)}</Descriptions.Item>
          <Descriptions.Item label="触发人">{pipelineDetail.triggered_by}</Descriptions.Item>
          {pipelineDetail.retry_of_run_id && <Descriptions.Item label="来源运行">#{pipelineDetail.retry_of_run_id}</Descriptions.Item>}
          {pipelineDetail.resume_from_task_id && <Descriptions.Item label="续跑起点">任务 #{pipelineDetail.resume_from_task_id}</Descriptions.Item>}
          {pipelineDetail.error_message && <Descriptions.Item label="错误" span={3}>{pipelineDetail.error_message}</Descriptions.Item>}
        </Descriptions>
        <Table<PipelineNodeRun> rowKey="id" size="small" pagination={false} dataSource={pipelineDetail.nodes} columns={[
          { title: '#', dataIndex: 'sequence_no', width: 54 },
          { title: '节点', dataIndex: 'task_name' },
          { title: '类型', dataIndex: 'task_type', width: 90 },
          { title: '状态', dataIndex: 'status', width: 100, render: statusTag },
          { title: '实例', dataIndex: 'instance_id', width: 80, render: (value?: number) => value ?? '-' },
          { title: '说明', dataIndex: 'message', ellipsis: true, render: (value: string | undefined, row: PipelineNodeRun) => value || (row.waiver_id ? `豁免 #${row.waiver_id}` : '-') },
          { title: '操作', width: 164, render: (_, node: PipelineNodeRun) => <Space size={6}>
            {canRetryPipelineNode(pipelineDetail.status, node.status) && <Popconfirm title={`从「${node.task_name}」继续？`} description="已成功或豁免的上游不会重复执行。unknown 节点禁止重跑。"
                onConfirm={() => void retryFromNode(node)} okText="创建续跑" cancelText="返回">
                <Button size="small" loading={pipelineAction === `retry-${pipelineDetail.id}-${node.task_id}`}>从此续跑</Button>
              </Popconfirm>}
            {canResolveUnknownInstance(node.status, node.instance_id) && <Button size="small"
              loading={pipelineAction === `resolve-${node.instance_id}`}
              onClick={() => openResolution(node.instance_id!, `流水线节点「${node.task_name}」`, true)}>确认结果</Button>}
          </Space> }
        ]} />
      </Space>}
    </Drawer>
    <Modal title={resolutionTarget ? `确认 ${resolutionTarget.label} 的远端结果` : '确认远端结果'}
      open={resolutionTarget !== undefined} onCancel={() => setResolutionTarget(undefined)}
      onOk={() => void submitResolution()} okText="写入不可变核对记录" cancelText="返回"
      confirmLoading={resolutionTarget ? pipelineAction === `resolve-${resolutionTarget.instanceId}` : false}
      destroyOnClose>
      <Alert type="warning" showIcon style={{ marginBottom: 16 }}
        message="仅用于没有受管句柄、且已通过外部系统确认结果的 unknown Spark 执行。提交后不可修改或删除。" />
      <Form form={resolutionForm} layout="vertical" preserve={false}>
        <Form.Item name="status" label="确认结果" rules={[{ required: true, message: '请选择确认结果' }]}>
          <Select placeholder="选择外部系统确认的最终状态" options={[
            { value: 'success', label: '成功（流水线继续）' },
            { value: 'failed', label: '失败（流水线终止，可断点续跑）' },
            { value: 'cancelled', label: '已取消（流水线终止，可断点续跑）' }
          ]} />
        </Form.Item>
        <Form.Item name="evidenceRef" label="外部证据引用" rules={[{ required: true, whitespace: true, max: 512,
          message: '请填写 application ID、日志地址或工单编号（最多 512 字）' }]}>
          <Input placeholder="例如 application_... / 日志 URL / 工单编号" maxLength={512} />
        </Form.Item>
        <Form.Item name="reason" label="核对说明" rules={[{ required: true, whitespace: true, max: 512,
          message: '请说明核对依据和结论（最多 512 字）' }]}>
          <Input.TextArea rows={4} maxLength={512} showCount placeholder="说明在哪里核对、由什么证据得出该结论" />
        </Form.Item>
      </Form>
    </Modal>
  </div>
}
