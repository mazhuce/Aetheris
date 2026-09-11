import { useEffect, useMemo, useState } from 'react'
import { Alert, App, Button, Card, Col, DatePicker, Descriptions, Drawer, Form, Input, InputNumber, Modal, Popconfirm, Row, Select, Space, Table, Tabs, Tag, Typography } from 'antd'
import { BranchesOutlined, CaretRightOutlined, PlusOutlined, ReloadOutlined, SafetyCertificateOutlined, StopOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import {
  approveSparkArtifact, cancelPipeline, cancelTaskInstance, changeGitRepositoryStatus, changeGitRepositoryTrust, changeSparkRunnerStatus,
  getPipelineRun, getSparkHealth, listAllInstances, listGitRepositories, listGitRevisions, listPipelineRuns,
  listSparkArtifactVerifications, listSparkArtifacts,
  listSparkEnvironments, listSparkJobs, listSparkRunners, listTasks, registerGitRepository, registerSparkArtifact,
  resolveTaskInstance, revokeSparkArtifact, runTask, startPipeline, synchronizeGitRepository, registerSparkRunner,
  retryPipeline, verifySparkArtifact,
  type ExecutionResolutionInput,
  type GitRepository, type GitRepositoryInput, type GitRepositoryTrustInput, type GitRevision, type ManagedArtifact, type ManagedArtifactInput,
  type PipelineDetail, type PipelineNodeRun, type PipelineRun, type SourceVerification,
  type SparkEnvironment, type SparkJobDefinition,
  type SparkRunner, type SparkRunnerHealth, type SparkRunnerInput, type TaskInstance, type TaskView
} from '@/api/workflow'
import { pipelineTargetOptions, sparkTaskOptions } from './taskOptions'
import { canCancelPipeline, canResolveUnknownInstance, canRetryPipelineNode } from './pipelineActions'
import { getCurrentRole } from '@/utils/access'

export default function SparkDevelopmentPage() {
  const { message } = App.useApp()
  const [health, setHealth] = useState<SparkRunnerHealth | null>(null)
  const [environments, setEnvironments] = useState<SparkEnvironment[]>([])
  const [runners, setRunners] = useState<SparkRunner[]>([])
  const [repositories, setRepositories] = useState<GitRepository[]>([])
  const [revisions, setRevisions] = useState<GitRevision[]>([])
  const [revisionRepository, setRevisionRepository] = useState<GitRepository>()
  const [verifications, setVerifications] = useState<SourceVerification[]>([])
  const [verificationArtifact, setVerificationArtifact] = useState<ManagedArtifact>()
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
  const [runnerEditorOpen, setRunnerEditorOpen] = useState(false)
  const [repositoryEditorOpen, setRepositoryEditorOpen] = useState(false)
  const [trustRepository, setTrustRepository] = useState<GitRepository>()
  const [artifactEditorOpen, setArtifactEditorOpen] = useState(false)
  const [resolutionTarget, setResolutionTarget] = useState<{ instanceId: number; label: string; pipeline: boolean }>()
  const [resolutionForm] = Form.useForm<ExecutionResolutionInput>()
  const [runnerForm] = Form.useForm<SparkRunnerInput>()
  const [repositoryForm] = Form.useForm<GitRepositoryInput & { trustedSignerText?: string }>()
  const [trustForm] = Form.useForm<GitRepositoryTrustInput & { trustedSignerText?: string }>()
  const [artifactForm] = Form.useForm<ManagedArtifactInput>()
  const isAdmin = getCurrentRole() === 'admin'

  const refresh = async () => {
    const settled = await Promise.allSettled([
      getSparkHealth(), listSparkEnvironments(), isAdmin ? listSparkRunners() : Promise.resolve([]),
      isAdmin ? listGitRepositories() : Promise.resolve([]), listSparkArtifacts(), listSparkJobs(),
      listTasks(), listAllInstances(undefined, 100), listPipelineRuns()
    ])
    if (settled[0].status === 'fulfilled') setHealth(settled[0].value)
    else setHealth({ status: 'DOWN', activeJobs: 0, maxConcurrency: 0 })
    if (settled[1].status === 'fulfilled') setEnvironments(settled[1].value)
    if (settled[2].status === 'fulfilled') setRunners(settled[2].value)
    if (settled[3].status === 'fulfilled') setRepositories(settled[3].value)
    if (settled[4].status === 'fulfilled') setArtifacts(settled[4].value)
    if (settled[5].status === 'fulfilled') setJobs(settled[5].value)
    if (settled[6].status === 'fulfilled') setTasks(settled[6].value)
    if (settled[7].status === 'fulfilled') setInstances(settled[7].value)
    if (settled[8].status === 'fulfilled') setPipelines(settled[8].value)
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

  const createRunner = async () => {
    let values: SparkRunnerInput
    try { values = await runnerForm.validateFields() }
    catch { return }
    setPipelineAction('runner-create')
    try {
      await registerSparkRunner(values)
      message.success('Runner 已完成健康校验并注册')
      setRunnerEditorOpen(false)
      runnerForm.resetFields()
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Runner 注册失败')
    } finally { setPipelineAction(undefined) }
  }

  const updateRunnerStatus = async (runner: SparkRunner, status: SparkRunner['status']) => {
    const action = `runner-${runner.id}-${status}`
    setPipelineAction(action)
    try {
      await changeSparkRunnerStatus(runner.id, status)
      message.success(status === 'active' ? 'Runner 已启用' : status === 'draining' ? 'Runner 已进入排空状态' : 'Runner 已停用')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Runner 状态修改失败')
    } finally { setPipelineAction(undefined) }
  }

  const createRepository = async () => {
    let values: GitRepositoryInput & { trustedSignerText?: string }
    try { values = await repositoryForm.validateFields() }
    catch { return }
    setPipelineAction('repository-create')
    try {
      const { trustedSignerText, ...repository } = values
      await registerGitRepository({ ...repository, trustedSignerFingerprints: trustedSignerText
        ? trustedSignerText.split(/[\s,]+/).map((value) => value.trim()).filter(Boolean) : [] })
      message.success('仓库分支、commit 签名策略和可信指纹已验证')
      setRepositoryEditorOpen(false)
      repositoryForm.resetFields()
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Git 仓库注册失败')
    } finally { setPipelineAction(undefined) }
  }

  const updateRepositoryStatus = async (repository: GitRepository, status: GitRepository['status']) => {
    const action = `repository-${repository.id}-${status}`
    setPipelineAction(action)
    try {
      await changeGitRepositoryStatus(repository.id, status)
      message.success(status === 'active' ? 'Git 仓库已重新验证并启用' : 'Git 仓库已停用')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Git 仓库状态修改失败')
    } finally { setPipelineAction(undefined) }
  }

  const syncRepository = async (repository: GitRepository) => {
    const action = `repository-${repository.id}-sync`
    setPipelineAction(action)
    try {
      await synchronizeGitRepository(repository.id)
      message.success('远端分支已同步，修订记录已保存')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Git 仓库同步失败')
    } finally { setPipelineAction(undefined) }
  }

  const showRevisions = async (repository: GitRepository) => {
    const action = `repository-${repository.id}-history`
    setPipelineAction(action)
    try {
      setRevisions(await listGitRevisions(repository.id))
      setRevisionRepository(repository)
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || 'Git 修订历史加载失败')
    } finally { setPipelineAction(undefined) }
  }

  const openTrustEditor = (repository: GitRepository) => {
    trustForm.setFieldsValue({ signaturePolicy: repository.signaturePolicy,
      trustedSignerText: repository.trustedSignerFingerprints.join('\n') })
    setTrustRepository(repository)
  }

  const updateRepositoryTrust = async () => {
    if (!trustRepository) return
    let values: GitRepositoryTrustInput & { trustedSignerText?: string }
    try { values = await trustForm.validateFields() }
    catch { return }
    const { trustedSignerText, ...trust } = values
    setPipelineAction(`repository-${trustRepository.id}-trust`)
    try {
      await changeGitRepositoryTrust(trustRepository.id, { ...trust, trustedSignerFingerprints: trustedSignerText
        ? trustedSignerText.split(/[\s,]+/).map((value) => value.trim()).filter(Boolean) : [] })
      message.success('可信签名策略已验证并完成轮换')
      setTrustRepository(undefined)
      trustForm.resetFields()
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '信任策略更新失败')
    } finally { setPipelineAction(undefined) }
  }

  const createArtifact = async () => {
    let values: ManagedArtifactInput
    try { values = await artifactForm.validateFields() }
    catch { return }
    setPipelineAction('artifact-create')
    try {
      await registerSparkArtifact(values)
      message.success('制品已绑定不可变 Git 修订，等待其他管理员复核')
      setArtifactEditorOpen(false)
      artifactForm.resetFields()
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '制品登记失败')
    } finally { setPipelineAction(undefined) }
  }

  const updateArtifact = async (artifact: ManagedArtifact, action: 'approve' | 'revoke') => {
    const key = `artifact-${artifact.id}-${action}`
    setPipelineAction(key)
    try {
      if (action === 'approve') await approveSparkArtifact(artifact.id)
      else await revokeSparkArtifact(artifact.id)
      message.success(action === 'approve' ? '制品已复核通过' : '制品已撤销')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '制品状态修改失败')
    } finally { setPipelineAction(undefined) }
  }

  const verifyArtifact = async (artifact: ManagedArtifact) => {
    const key = `artifact-${artifact.id}-verify`
    setPipelineAction(key)
    try {
      await verifySparkArtifact(artifact.id)
      message.success('Git blob、commit 签名和运行器文件摘要均已重新验证')
      await refresh()
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '制品验证失败')
    } finally { setPipelineAction(undefined) }
  }

  const showVerifications = async (artifact: ManagedArtifact) => {
    const key = `artifact-${artifact.id}-history`
    setPipelineAction(key)
    try {
      setVerifications(await listSparkArtifactVerifications(artifact.id))
      setVerificationArtifact(artifact)
    } catch (error) {
      message.error((error as { response?: { data?: { message?: string } } }).response?.data?.message || '验证证据加载失败')
    } finally { setPipelineAction(undefined) }
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
    const color = status === 'succeeded' || status === 'success' || status === 'verified' ? 'success'
      : status === 'failed' ? 'error'
        : status === 'running' ? 'processing'
          : status === 'waived' || status === 'skipped' || status === 'unknown' || status === 'unsigned' || status === 'not_checked' ? 'warning' : 'default'
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
          {['queued', 'submitting', 'running', 'cancel_requested'].includes(row.status) && <Button danger size="small" icon={<StopOutlined />} onClick={() => void cancel(row.id)}>取消</Button>}
          {canResolveUnknownInstance(row.status, row.id) && <Button size="small" onClick={() => openResolution(row.id, `Spark 实例 #${row.id}`)}>确认结果</Button>}
        </Space> }
      ]} /></DataSurface> },
      { key: 'jobs', label: '作业定义', children: <Table rowKey="id" size="small" dataSource={jobs} pagination={{ pageSize: 10 }} columns={[
        { title: '名称', dataIndex: 'name' }, { title: '版本', dataIndex: 'version' }, { title: '制品', dataIndex: 'artifactKey' },
        { title: '输入', dataIndex: 'inputTables', render: (v: string[]) => v?.join(', ') }, { title: '输出', dataIndex: 'outputUri' },
        { title: '资源', render: (_, v: SparkJobDefinition) => `${v.driverCores}C / ${v.driverMemoryMb}MB` }, { title: '状态', dataIndex: 'status' }
      ]} /> },
      { key: 'artifacts', label: '制品与环境', children: <Space direction="vertical" className="full-width-control">
        <Space className="full-width-control" style={{ justifyContent: 'space-between' }}>
          <Typography.Text type="secondary">源码从固定 commit 自动计算摘要；审批、发布和执行均保留不可变验证证据。</Typography.Text>
          {isAdmin && <Space wrap>
            <Button icon={<BranchesOutlined />} onClick={() => {
              repositoryForm.setFieldsValue({ defaultBranch: 'main', signaturePolicy: 'required' })
              setRepositoryEditorOpen(true)
            }}>注册仓库</Button>
            <Button icon={<PlusOutlined />} disabled={!repositories.some((item) => item.status === 'active')} onClick={() => {
              const repository = repositories.find((item) => item.status === 'active')
              artifactForm.setFieldsValue({ repositoryId: repository?.id, commitSha: repository?.currentCommitSha })
              setArtifactEditorOpen(true)
            }}>登记制品</Button>
            <Button type="primary" icon={<PlusOutlined />} onClick={() => {
              runnerForm.setFieldsValue({ maxCores: 2, maxMemoryMb: 2048, maxConcurrency: 1 })
              setRunnerEditorOpen(true)
            }}>注册 Runner</Button>
          </Space>}
        </Space>
        {isAdmin && <Table<GitRepository> rowKey="id" size="small" dataSource={repositories} pagination={false} scroll={{ x: 1080 }} columns={[
          { title: 'Git 仓库', dataIndex: 'name', width: 150 },
          { title: '远端', dataIndex: 'remoteUrl', ellipsis: true, width: 260 },
          { title: '分支', dataIndex: 'defaultBranch', width: 120 },
          { title: '当前 commit', dataIndex: 'currentCommitSha', width: 190, render: (value: string) => <Typography.Text code copyable>{value.slice(0, 12)}</Typography.Text> },
          { title: '签名策略', dataIndex: 'signaturePolicy', width: 100, render: (value: string) => <Tag color={value === 'required' ? 'success' : 'warning'}>{value === 'required' ? '强制' : '可选'}</Tag> },
          { title: '凭据', dataIndex: 'credentialConfigured', width: 76, render: (value: boolean) => value ? '环境引用' : '公开' },
          { title: '状态', dataIndex: 'status', width: 90, render: statusTag },
          { title: '操作', fixed: 'right', width: 290, render: (_, value) => <Space size={6}>
            <Button size="small" loading={pipelineAction === `repository-${value.id}-history`} onClick={() => void showRevisions(value)}>历史</Button>
            <Button size="small" loading={pipelineAction === `repository-${value.id}-trust`} onClick={() => openTrustEditor(value)}>信任</Button>
            {value.status === 'active' ? <>
              <Button size="small" loading={pipelineAction === `repository-${value.id}-sync`} onClick={() => void syncRepository(value)}>同步</Button>
              <Popconfirm title="停用此 Git 仓库？" description="已发布作业保留，但新的制品审批和作业发布会被阻止。"
                onConfirm={() => void updateRepositoryStatus(value, 'disabled')} okText="确认停用" cancelText="返回">
                <Button danger size="small" loading={pipelineAction === `repository-${value.id}-disabled`}>停用</Button>
              </Popconfirm>
            </> : <Button size="small" loading={pipelineAction === `repository-${value.id}-active`}
              onClick={() => void updateRepositoryStatus(value, 'active')}>验证并启用</Button>}
          </Space> }
        ]} />}
        <Table<SparkRunner> rowKey="id" size="small" dataSource={runners} pagination={false} columns={[
          { title: 'Runner', dataIndex: 'name' }, { title: '环境', dataIndex: 'environmentName' },
          { title: '状态', dataIndex: 'status', render: statusTag },
          { title: '作业', render: (_, value) => `${value.activeJobs} / ${value.maxConcurrency}` },
          { title: '核心', render: (_, value) => `${value.usedCores} / ${value.maxCores}` },
          { title: '内存', render: (_, value) => `${value.usedMemoryMb} / ${value.maxMemoryMb} MB` },
          { title: '排队', dataIndex: 'queuedJobs' },
          ...(isAdmin ? [{ title: '操作', width: 190, render: (_: unknown, value: SparkRunner) => <Space size={6}>
            {value.status === 'active' && <Popconfirm title="将 Runner 置为排空状态？" description="不再接收新任务，现有任务继续运行。"
              onConfirm={() => void updateRunnerStatus(value, 'draining')} okText="开始排空" cancelText="返回">
              <Button size="small" loading={pipelineAction === `runner-${value.id}-draining`}>排空</Button>
            </Popconfirm>}
            {value.status !== 'active' && <Button size="small" loading={pipelineAction === `runner-${value.id}-active`}
              onClick={() => void updateRunnerStatus(value, 'active')}>启用</Button>}
            {value.status === 'draining' && <Popconfirm title="确认停用 Runner？" description="仍有活动作业时服务端会拒绝停用。"
              onConfirm={() => void updateRunnerStatus(value, 'disabled')} okText="确认停用" cancelText="返回">
              <Button danger size="small" loading={pipelineAction === `runner-${value.id}-disabled`}>停用</Button>
            </Popconfirm>}
          </Space> }] : [])
        ]} />
        <Table<ManagedArtifact> rowKey="id" size="small" dataSource={artifacts} pagination={false} scroll={{ x: 1280 }} columns={[
          { title: '制品', dataIndex: 'name' }, { title: '版本', dataIndex: 'version' }, { title: '文件', dataIndex: 'artifactKey' },
          { title: 'Git 来源', width: 230, render: (_, value) => value.sourceRepositoryKey
            ? `${value.sourceRepositoryKey}@${value.commitSha?.slice(0, 10)}` : '历史制品' },
          { title: '源码路径', dataIndex: 'sourcePath', ellipsis: true },
          { title: 'Blob SHA-256', dataIndex: 'sourceBlobSha256', ellipsis: true, render: (value?: string) => value ? <Typography.Text code>{value.slice(0, 12)}</Typography.Text> : <Tag color="warning">待验证</Tag> },
          { title: 'Commit 签名', dataIndex: 'sourceSignatureStatus', width: 110, render: (value?: string) => value ? statusTag(value) : <Tag color="warning">待验证</Tag> },
          { title: '状态', dataIndex: 'status', render: statusTag },
          ...(isAdmin ? [{ title: '操作', fixed: 'right' as const, width: 250, render: (_: unknown, value: ManagedArtifact) => <Space size={6}>
            {value.sourceRepositoryId && <Button size="small" icon={<SafetyCertificateOutlined />}
              loading={pipelineAction === `artifact-${value.id}-history`} onClick={() => void showVerifications(value)}>证据</Button>}
            {value.sourceRepositoryId && value.status !== 'revoked' && <Button size="small"
              loading={pipelineAction === `artifact-${value.id}-verify`} onClick={() => void verifyArtifact(value)}>复验</Button>}
            {value.status === 'draft' && <Button size="small" loading={pipelineAction === `artifact-${value.id}-approve`}
              onClick={() => void updateArtifact(value, 'approve')}>复核</Button>}
            {value.status === 'approved' && <Popconfirm title="撤销此制品？" description="引用它的已发布作业不会被改写，但新发布会被阻止。"
              onConfirm={() => void updateArtifact(value, 'revoke')} okText="确认撤销" cancelText="返回">
              <Button danger size="small" loading={pipelineAction === `artifact-${value.id}-revoke`}>撤销</Button>
            </Popconfirm>}
          </Space> }] : [])
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
    <Drawer title={revisionRepository ? `${revisionRepository.name} · 修订历史` : 'Git 修订历史'} width={820}
      open={revisionRepository !== undefined} onClose={() => setRevisionRepository(undefined)}>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="每次注册、同步或重新启用只追加远端分支当时的完整 commit SHA；历史记录不会被覆盖。" />
      <Table<GitRevision> rowKey="id" size="small" pagination={false} dataSource={revisions} columns={[
        { title: '分支', dataIndex: 'branchName', width: 130 },
        { title: 'commit', dataIndex: 'commitSha', render: (value: string) => <Typography.Text code copyable>{value}</Typography.Text> },
        { title: '签名', dataIndex: 'signatureStatus', width: 100, render: statusTag },
        { title: '签名指纹', dataIndex: 'signerFingerprint', ellipsis: true, render: (value?: string) => value ? <Typography.Text code copyable>{value.slice(0, 16)}</Typography.Text> : '-' },
        { title: '发现人', dataIndex: 'discoveredBy', width: 110 },
        { title: '发现时间', dataIndex: 'discoveredAt', width: 180, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') }
      ]} />
    </Drawer>
    <Drawer title={verificationArtifact ? `${verificationArtifact.name} · 供应链验证证据` : '供应链验证证据'} width={920}
      open={verificationArtifact !== undefined} onClose={() => setVerificationArtifact(undefined)}>
      <Alert type="success" showIcon style={{ marginBottom: 16 }}
        message="每次登记、人工复验、审批和发布都会重新读取固定 Git blob，并追加不可修改的摘要与签名证据。" />
      <Table<SourceVerification> rowKey="id" size="small" pagination={false} dataSource={verifications} scroll={{ x: 1050 }} columns={[
        { title: '用途', dataIndex: 'purpose', width: 100 },
        { title: 'commit', dataIndex: 'commitSha', width: 130, render: (value: string) => <Typography.Text code>{value.slice(0, 12)}</Typography.Text> },
        { title: 'blob', dataIndex: 'blobId', width: 130, render: (value: string) => <Typography.Text code>{value.slice(0, 12)}</Typography.Text> },
        { title: 'SHA-256', dataIndex: 'blobSha256', width: 160, render: (value: string) => <Typography.Text code copyable>{value.slice(0, 16)}</Typography.Text> },
        { title: '大小', dataIndex: 'blobSize', width: 90, render: (value: number) => `${value} B` },
        { title: '签名', dataIndex: 'signatureStatus', width: 100, render: statusTag },
        { title: '验证人', dataIndex: 'verifiedBy', width: 110 },
        { title: '验证时间', dataIndex: 'verifiedAt', width: 180, render: (value: string) => dayjs(value).format('YYYY-MM-DD HH:mm:ss') }
      ]} />
    </Drawer>
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
    <Modal title="注册 Git 仓库" open={repositoryEditorOpen} onCancel={() => setRepositoryEditorOpen(false)}
      onOk={() => void createRepository()} okText="验证远端并注册" cancelText="返回"
      confirmLoading={pipelineAction === 'repository-create'} destroyOnClose>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="强制签名仓库要求 commit 由可信 OpenPGP 指纹签署，公钥由 workflow 服务的只读 GnuPG keyring 提供；token 和私钥都不会写入平台。" />
      <Form form={repositoryForm} layout="vertical" preserve={false}>
        <Row gutter={12}>
          <Col span={10}><Form.Item name="repositoryKey" label="仓库标识" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9_-]{0,63}$/, message: '使用小写字母、数字、下划线或连字符' }]}>
            <Input maxLength={64} placeholder="spark-jobs" />
          </Form.Item></Col>
          <Col span={14}><Form.Item name="name" label="仓库名称" rules={[{ required: true, whitespace: true, max: 128 }]}>
            <Input maxLength={128} placeholder="生产 PySpark 作业" />
          </Form.Item></Col>
        </Row>
        <Form.Item name="remoteUrl" label="HTTPS 远端地址" rules={[{ required: true, type: 'url', message: '请输入合法 HTTPS 地址' }, {
          validator: (_, value?: string) => !value || value.startsWith('https://') ? Promise.resolve() : Promise.reject(new Error('只允许 HTTPS'))
        }]}>
          <Input placeholder="https://github.com/organization/jobs.git" />
        </Form.Item>
        <Form.Item name="defaultBranch" label="默认分支" rules={[{ required: true, pattern: /^[A-Za-z0-9][A-Za-z0-9._/-]{0,127}$/, message: '请输入合法 Git 分支名称' }]}>
          <Input maxLength={128} placeholder="main" />
        </Form.Item>
        <Row gutter={12}>
          <Col span={9}><Form.Item name="signaturePolicy" label="Commit 签名策略" rules={[{ required: true }]}>
            <Select options={[{ value: 'required', label: '强制可信签名' }, { value: 'optional', label: '允许未签名' }]} />
          </Form.Item></Col>
          <Col span={15}><Form.Item name="trustedSignerText" label="可信 OpenPGP 指纹" dependencies={['signaturePolicy']}
            rules={[({ getFieldValue }) => ({ validator: (_, value?: string) => {
              const entries = value?.split(/[\s,]+/).filter(Boolean) || []
              if (getFieldValue('signaturePolicy') === 'required' && entries.length === 0) return Promise.reject(new Error('强制签名至少需要一个可信指纹'))
              return entries.every((item) => /^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$/.test(item)) ? Promise.resolve()
                : Promise.reject(new Error('每个指纹必须为 40 或 64 位十六进制值'))
            } })]}>
            <Input.TextArea rows={2} maxLength={4096} placeholder="多个指纹使用逗号或换行分隔" />
          </Form.Item></Col>
        </Row>
        <Row gutter={12}>
          <Col span={10}><Form.Item name="credentialUsername" label="凭据用户名（私有仓库）" dependencies={['credentialRef']}
            rules={[({ getFieldValue }) => ({ validator: (_, value?: string) => !getFieldValue('credentialRef') || value?.trim()
              ? Promise.resolve() : Promise.reject(new Error('填写凭据引用时必须填写用户名')) })]}>
            <Input maxLength={128} placeholder="git" />
          </Form.Item></Col>
          <Col span={14}><Form.Item name="credentialRef" label="凭据环境变量引用" rules={[{
            pattern: /^env:\/\/AETHERIS_GIT_CREDENTIAL_[A-Z0-9_]{1,64}$/,
            message: '格式：env://AETHERIS_GIT_CREDENTIAL_*'
          }]}>
            <Input maxLength={128} placeholder="env://AETHERIS_GIT_CREDENTIAL_JOBS" />
          </Form.Item></Col>
        </Row>
      </Form>
    </Modal>
    <Modal title={trustRepository ? `${trustRepository.name} · 信任策略轮换` : '信任策略轮换'}
      open={trustRepository !== undefined} onCancel={() => setTrustRepository(undefined)}
      onOk={() => void updateRepositoryTrust()} okText="验证当前 commit 并更新" cancelText="返回"
      confirmLoading={trustRepository ? pipelineAction === `repository-${trustRepository.id}-trust` : false} destroyOnClose>
      <Alert type="warning" showIcon style={{ marginBottom: 16 }}
        message="新策略会先对当前远端 commit 做密码学验证；验证失败或仓库配置并发变化时不会写入。策略变更将追加到不可修改的审计历史。" />
      <Form form={trustForm} layout="vertical" preserve={false}>
        <Form.Item name="signaturePolicy" label="Commit 签名策略" rules={[{ required: true }]}>
          <Select options={[{ value: 'required', label: '强制可信签名' }, { value: 'optional', label: '允许未签名' }]} />
        </Form.Item>
        <Form.Item name="trustedSignerText" label="可信 OpenPGP 指纹" dependencies={['signaturePolicy']}
          rules={[({ getFieldValue }) => ({ validator: (_, value?: string) => {
            const entries = value?.split(/[\s,]+/).filter(Boolean) || []
            if (getFieldValue('signaturePolicy') === 'required' && entries.length === 0) return Promise.reject(new Error('强制签名至少需要一个可信指纹'))
            return entries.every((item) => /^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$/.test(item)) ? Promise.resolve()
              : Promise.reject(new Error('每个指纹必须为 40 或 64 位十六进制值'))
          } })]}>
          <Input.TextArea rows={4} maxLength={4096} placeholder="多个指纹使用逗号或换行分隔" />
        </Form.Item>
      </Form>
    </Modal>
    <Modal title="登记 Git 绑定制品" open={artifactEditorOpen} onCancel={() => setArtifactEditorOpen(false)}
      onOk={() => void createArtifact()} okText="登记草稿" cancelText="返回"
      confirmLoading={pipelineAction === 'artifact-create'} destroyOnClose>
      <Alert type="warning" showIcon style={{ marginBottom: 16 }}
        message="服务端将从固定 commit 读取源码并自动计算 SHA-256，再与运行器白名单文件比对；填写预期摘要时不一致会直接拒绝。" />
      <Form form={artifactForm} layout="vertical" preserve={false}>
        <Row gutter={12}>
          <Col span={14}><Form.Item name="name" label="制品名称" rules={[{ required: true, whitespace: true, max: 128 }]}>
            <Input maxLength={128} />
          </Form.Item></Col>
          <Col span={10}><Form.Item name="version" label="版本" rules={[{ required: true, whitespace: true, max: 32 }]}>
            <Input maxLength={32} placeholder="1.0.0" />
          </Form.Item></Col>
        </Row>
        <Form.Item name="repositoryId" label="Git 仓库" rules={[{ required: true, message: '请选择活动仓库' }]}>
          <Select options={repositories.filter((item) => item.status === 'active').map((item) => ({ value: item.id, label: `${item.name} · ${item.defaultBranch}` }))}
            onChange={(id: number) => artifactForm.setFieldValue('commitSha', repositories.find((item) => item.id === id)?.currentCommitSha)} />
        </Form.Item>
        <Form.Item name="commitSha" label="完整 commit SHA" rules={[{ required: true, pattern: /^[0-9a-f]{40}$/, message: '请输入仓库已发现的 40 位小写 commit SHA' }]}>
          <Input maxLength={40} />
        </Form.Item>
        <Row gutter={12}>
          <Col span={10}><Form.Item name="artifactKey" label="运行器制品键" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9_-]{0,63}\.py$/, message: '例如 order_daily.py' }]}>
            <Input maxLength={67} placeholder="order_daily.py" />
          </Form.Item></Col>
          <Col span={14}><Form.Item name="sourcePath" label="仓库源码路径" rules={[{ required: true, pattern: /^[A-Za-z0-9._-]+(?:\/[A-Za-z0-9._-]+)*\.py$/, message: '请输入仓库内相对 .py 路径' }]}>
            <Input maxLength={512} placeholder="spark/order_daily.py" />
          </Form.Item></Col>
        </Row>
        <Form.Item name="sha256" label="预期 SHA-256（可选）" rules={[{ pattern: /^[0-9a-f]{64}$/, message: '请输入 64 位小写 SHA-256' }]}
          extra="留空时完全使用 Git blob 自动计算结果；填写后用于额外防误选。">
          <Input maxLength={64} placeholder="由服务端自动计算" />
        </Form.Item>
      </Form>
    </Modal>
    <Modal title="注册 Spark Runner" open={runnerEditorOpen} onCancel={() => setRunnerEditorOpen(false)}
      onOk={() => void createRunner()} okText="健康校验并注册" cancelText="返回"
      confirmLoading={pipelineAction === 'runner-create'} destroyOnClose>
      <Alert type="info" showIcon style={{ marginBottom: 16 }}
        message="地址仅允许无凭据、无路径的 HTTP(S) 基础地址；注册前会执行健康检查。所有 Runner 使用独立的受管令牌。" />
      <Form form={runnerForm} layout="vertical" preserve={false}>
        <Form.Item name="environmentId" label="计算环境" rules={[{ required: true, message: '请选择计算环境' }]}>
          <Select options={environments.filter((item) => item.status === 'active').map((item) => ({ value: item.id, label: item.name }))} />
        </Form.Item>
        <Form.Item name="runnerKey" label="Runner 标识" rules={[{ required: true, pattern: /^[a-z0-9][a-z0-9_-]{0,63}$/, message: '使用小写字母、数字、下划线或连字符，最长 64 位' }]}>
          <Input maxLength={64} placeholder="例如 spark-runner-02" />
        </Form.Item>
        <Form.Item name="name" label="Runner 名称" rules={[{ required: true, whitespace: true, max: 128 }]}>
          <Input maxLength={128} />
        </Form.Item>
        <Form.Item name="baseUrl" label="基础地址" rules={[{ required: true, type: 'url', message: '请输入 HTTP(S) 地址' }]}>
          <Input placeholder="http://spark-runner-02:18081" />
        </Form.Item>
        <Row gutter={12}>
          <Col span={8}><Form.Item name="maxCores" label="核心上限" rules={[{ required: true }]}>
            <InputNumber min={1} max={256} precision={0} className="full-width-control" />
          </Form.Item></Col>
          <Col span={8}><Form.Item name="maxMemoryMb" label="内存上限 MB" rules={[{ required: true }]}>
            <InputNumber min={512} max={1048576} precision={0} className="full-width-control" />
          </Form.Item></Col>
          <Col span={8}><Form.Item name="maxConcurrency" label="并发上限" rules={[{ required: true }]}>
            <InputNumber min={1} max={256} precision={0} className="full-width-control" />
          </Form.Item></Col>
        </Row>
      </Form>
    </Modal>
  </div>
}
