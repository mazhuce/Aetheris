import http from './http'

/** 任务定义。字段与后端 workflow.task 表一致。 */
export interface Task {
  id: number
  name: string
  taskType?: 'sql' | 'spark' | 'quality'
  datasourceId?: number
  datasourceName?: string
  taskSql?: string
  jobDefinitionId?: number
  qualityRuleId?: number
  /** Quartz cron（6 或 7 段）；为空表示仅手动触发。 */
  cronExpression?: string
  targetTable?: string
  timeoutSeconds?: number
  maxRetries?: number
  status?: 'enabled' | 'disabled'
  owner?: string
  description?: string
  createdAt?: string
  updatedAt?: string
}

export interface TaskInput {
  name: string
  taskType?: 'sql' | 'spark' | 'quality'
  datasourceId?: number
  datasourceName?: string
  taskSql?: string
  jobDefinitionId?: number
  qualityRuleId?: number
  cronExpression?: string
  targetTable?: string
  timeoutSeconds?: number
  maxRetries?: number
  status?: string
  description?: string
}

/** 列表视图：任务定义 + 调度状态 + 最近一次结果，后端一次返回避免 N+1 请求。 */
export interface TaskView {
  task: Task
  nextFireTime?: string
  lastStatus?: TaskInstanceStatus
  lastBizDate?: string
  lastFinishedAt?: string
  upstreamCount: number
}

export type TaskInstanceStatus =
  | 'pending'
  | 'submitting'
  | 'running'
  | 'cancel_requested'
  | 'cancelled'
  | 'success'
  | 'failed'
  | 'timed_out'
  | 'unknown'
  | 'skipped'

export interface TaskInstance {
  id: number
  taskId: number
  /** 业务日期（数据日期），对应分区键 dt，不是执行时刻。 */
  bizDate: string
  status: TaskInstanceStatus
  triggerType?: 'manual' | 'schedule' | 'retry' | 'pipeline'
  attempt?: number
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  /** Hive 驱动不返回行数时为 -1，表示「未知」而非 0 行。 */
  affectedRows?: number
  errorMessage?: string
  triggeredBy?: string
  activeAttemptId?: number
  fencingToken?: number
  heartbeatAt?: string
  leaseExpiresAt?: string
}

export interface TaskLog {
  id: number
  instanceId: number
  attemptId?: number
  level: 'info' | 'warn' | 'error'
  message: string
  createdAt?: string
}

export type ExecutionAttemptStatus =
  | 'queued'
  | 'submitting'
  | 'running'
  | 'cancel_requested'
  | 'cancelled'
  | 'succeeded'
  | 'failed'
  | 'timed_out'
  | 'unknown'
  | 'skipped'

export interface ExecutionAttempt {
  id: number
  instanceId: number
  attemptNo: number
  status: ExecutionAttemptStatus
  fencingToken: number
  workerId: string
  triggerType: 'manual' | 'schedule' | 'retry' | 'pipeline'
  triggeredBy?: string
  taskName: string
  taskType?: 'sql' | 'spark' | 'quality'
  datasourceId?: number
  jobDefinitionId?: number
  jobVersion?: number
  qualityRuleId?: number
  taskUpdatedAt?: string
  definitionDigest: string
  parametersJson: string
  parameterDigest: string
  remoteHandle?: string
  applicationId?: string
  startedAt?: string
  heartbeatAt?: string
  leaseExpiresAt?: string
  finishedAt?: string
  durationMs?: number
  affectedRows?: number
  errorCode?: string
  errorMessage?: string
}

export interface TaskDependency {
  id: number
  taskId: number
  upstreamTaskId: number
}

/** 实例状态的中文名与配色，供表格与详情统一使用。 */
export const INSTANCE_STATUS_META: Record<
  TaskInstanceStatus,
  { label: string; color: string }
> = {
  pending: { label: '等待中', color: 'default' },
  submitting: { label: '提交中', color: 'processing' },
  running: { label: '执行中', color: 'processing' },
  cancel_requested: { label: '取消中', color: 'warning' },
  cancelled: { label: '已取消', color: 'default' },
  success: { label: '成功', color: 'success' },
  // skipped 是「依赖未就绪」而非故障，用 warning 与 failed 区分开，
  // 否则会让人误以为任务出错。
  skipped: { label: '已跳过', color: 'warning' },
  failed: { label: '失败', color: 'error' },
  timed_out: { label: '已超时', color: 'error' },
  unknown: { label: '待核对', color: 'warning' }
}

export const ATTEMPT_STATUS_META: Record<ExecutionAttemptStatus, { label: string; color: string }> = {
  queued: { label: '排队中', color: 'default' },
  submitting: { label: '提交中', color: 'processing' },
  running: { label: '执行中', color: 'processing' },
  cancel_requested: { label: '取消中', color: 'warning' },
  cancelled: { label: '已取消', color: 'default' },
  succeeded: { label: '成功', color: 'success' },
  failed: { label: '失败', color: 'error' },
  timed_out: { label: '已超时', color: 'error' },
  unknown: { label: '待核对', color: 'warning' },
  skipped: { label: '已跳过', color: 'warning' }
}

export function listTasks(): Promise<TaskView[]> {
  return http.get<TaskView[]>('/api/workflow/tasks').then((res) => res.data)
}

export function getTask(id: number): Promise<TaskView> {
  return http.get<TaskView>(`/api/workflow/tasks/${id}`).then((res) => res.data)
}

export function createTask(payload: TaskInput): Promise<TaskView> {
  return http.post<TaskView>('/api/workflow/tasks', payload).then((res) => res.data)
}

export function updateTask(id: number, payload: TaskInput): Promise<TaskView> {
  return http.put<TaskView>(`/api/workflow/tasks/${id}`, payload).then((res) => res.data)
}

export function deleteTask(id: number): Promise<void> {
  return http.delete(`/api/workflow/tasks/${id}`).then(() => undefined)
}

/**
 * 手动触发任务。
 *
 * 后端同步执行，加工任务可能跑很久，因此单独放宽超时；
 * 即便前端超时，执行状态仍会落在实例表里，刷新可见。
 */
export function runTask(id: number, bizDate?: string): Promise<TaskInstance> {
  return http
    .post<TaskInstance>(`/api/workflow/tasks/${id}/run`, { bizDate }, { timeout: 300000 })
    .then((res) => res.data)
}

export function listInstances(id: number, limit = 30): Promise<TaskInstance[]> {
  return http
    .get<TaskInstance[]>(`/api/workflow/tasks/${id}/instances`, { params: { limit } })
    .then((res) => res.data)
}

/** 全局实例列表（任务监控页），按开始时间倒序，可按状态过滤。 */
export function listAllInstances(status?: string, limit = 100): Promise<TaskInstance[]> {
  return http
    .get<TaskInstance[]>('/api/workflow/tasks/instances', {
      params: status ? { status, limit } : { limit }
    })
    .then((res) => res.data)
}

export function listInstanceLogs(instanceId: number): Promise<TaskLog[]> {
  return http
    .get<TaskLog[]>(`/api/workflow/tasks/instances/${instanceId}/logs`)
    .then((res) => res.data)
}

export function listExecutionAttempts(instanceId: number): Promise<ExecutionAttempt[]> {
  return http
    .get<ExecutionAttempt[]>(`/api/workflow/tasks/instances/${instanceId}/attempts`)
    .then((res) => res.data)
}

export function cancelTaskInstance(instanceId: number): Promise<TaskInstance> {
  return http.post<TaskInstance>(`/api/workflow/tasks/instances/${instanceId}/cancel`).then((res) => res.data)
}

export interface SparkEnvironment {
  id: number; environmentKey: string; name: string; adapterType: string; sparkVersion: string
  javaVersion: string; pythonVersion: string; master: string; maxCores: number
  maxMemoryMb: number; maxTimeoutSeconds: number; maxConcurrency: number; status: string
}
export interface ManagedArtifact {
  id: number; name: string; version: string; artifactKey: string; sha256: string; status: string
  createdBy?: string; approvedBy?: string; approvedAt?: string
}
export interface SparkJobDefinition {
  id: number; name: string; version: number; environmentId: number; environmentName: string
  artifactId: number; artifactName: string; artifactKey: string; artifactDigest: string
  artifactStatus: string; inputTables: string[]; outputTable?: string; outputUri: string
  driverCores: number; driverMemoryMb: number; timeoutSeconds: number; maxRetries: number
  status: string; definitionDigest?: string; owner: string; description?: string
}
export interface SparkRunnerHealth { status: string; version?: string; activeJobs: number; maxConcurrency: number; message?: string }

export const getSparkHealth = () => http.get<SparkRunnerHealth>('/api/workflow/spark/health').then((r) => r.data)
export const listSparkEnvironments = () => http.get<SparkEnvironment[]>('/api/workflow/spark/environments').then((r) => r.data)
export const listSparkArtifacts = () => http.get<ManagedArtifact[]>('/api/workflow/spark/artifacts').then((r) => r.data)
export const listSparkJobs = () => http.get<SparkJobDefinition[]>('/api/workflow/spark/jobs').then((r) => r.data)

export interface PipelineRun {
  id: number
  target_task_id: number
  target_task_name?: string
  biz_date: string
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'cancelled' | 'unknown'
  triggered_by: string
  started_at?: string
  finished_at?: string
  error_message?: string
  retry_of_run_id?: number
  resume_from_task_id?: number
  cancellation_requested?: boolean
}

export interface PipelineNodeRun {
  id: number
  pipeline_run_id: number
  task_id: number
  task_name: string
  task_type: 'sql' | 'spark' | 'quality'
  sequence_no: number
  status: 'queued' | 'running' | 'succeeded' | 'failed' | 'skipped' | 'waived' | 'unknown' | 'cancelled'
  instance_id?: number
  waiver_id?: number
  started_at?: string
  finished_at?: string
  message?: string
}

export interface PipelineDetail extends PipelineRun {
  nodes: PipelineNodeRun[]
}

export const listPipelineRuns = (limit = 30) =>
  http.get<PipelineRun[]>('/api/workflow/pipelines', { params: { limit } }).then((r) => r.data)
export const getPipelineRun = (id: number) =>
  http.get<PipelineDetail>(`/api/workflow/pipelines/${id}`).then((r) => r.data)
export const startPipeline = (targetTaskId: number, bizDate: string) =>
  http.post<PipelineRun>('/api/workflow/pipelines', { targetTaskId, bizDate }).then((r) => r.data)
export const cancelPipeline = (id: number) =>
  http.post<PipelineRun>(`/api/workflow/pipelines/${id}/cancel`).then((r) => r.data)
export const retryPipeline = (id: number, resumeTaskId: number) =>
  http.post<PipelineRun>(`/api/workflow/pipelines/${id}/retry`, { resumeTaskId }).then((r) => r.data)

export function listDependencies(id: number): Promise<TaskDependency[]> {
  return http
    .get<TaskDependency[]>(`/api/workflow/tasks/${id}/dependencies`)
    .then((res) => res.data)
}

export function addDependency(id: number, upstreamTaskId: number): Promise<TaskDependency> {
  return http
    .post<TaskDependency>(`/api/workflow/tasks/${id}/dependencies`, undefined, {
      params: { upstreamTaskId }
    })
    .then((res) => res.data)
}

export function removeDependency(id: number, upstreamTaskId: number): Promise<void> {
  return http
    .delete(`/api/workflow/tasks/${id}/dependencies/${upstreamTaskId}`)
    .then(() => undefined)
}

// —— 发布管理：数据开发产物的发布、审批与版本上线 ——

export type ReleaseStatus = 'pending' | 'approved' | 'rejected' | 'released' | 'rolled_back'

export interface ReleaseRecord {
  id: number
  releaseNo: string
  title: string
  releaseType?: 'table' | 'task' | 'dashboard' | 'api' | 'model'
  targetName: string
  targetVersion?: string
  changeSummary?: string
  status?: ReleaseStatus
  requestedBy?: string
  approvedBy?: string
  targetRefId?: number
  snapshotDigest?: string
  approvedDigest?: string
  approvedAt?: string
  qualityRuleId?: number
  qualityResultId?: number
  releasedAt?: string
  createdAt?: string
  updatedAt?: string
}

export interface ReleaseRecordInput {
  releaseNo: string
  title: string
  releaseType?: string
  targetName: string
  targetVersion?: string
  changeSummary?: string
  targetRefId?: number
  qualityRuleId?: number
}

export function listReleases(status?: string, releaseType?: string): Promise<ReleaseRecord[]> {
  return http
    .get<ReleaseRecord[]>('/api/workflow/releases', {
      params: { status: status || undefined, releaseType: releaseType || undefined }
    })
    .then((res) => res.data)
}

export function getRelease(id: number): Promise<ReleaseRecord> {
  return http.get<ReleaseRecord>(`/api/workflow/releases/${id}`).then((res) => res.data)
}

export function createRelease(payload: ReleaseRecordInput): Promise<ReleaseRecord> {
  return http.post<ReleaseRecord>('/api/workflow/releases', payload).then((res) => res.data)
}

export function updateRelease(id: number, payload: ReleaseRecordInput): Promise<ReleaseRecord> {
  return http.put<ReleaseRecord>(`/api/workflow/releases/${id}`, payload).then((res) => res.data)
}

export function deleteRelease(id: number): Promise<void> {
  return http.delete(`/api/workflow/releases/${id}`).then(() => undefined)
}

export function approveRelease(id: number): Promise<ReleaseRecord> {
  return http.post<ReleaseRecord>(`/api/workflow/releases/${id}/approve`, undefined).then((res) => res.data)
}

export function rejectRelease(id: number): Promise<ReleaseRecord> {
  return http.post<ReleaseRecord>(`/api/workflow/releases/${id}/reject`, undefined).then((res) => res.data)
}

export function publishRelease(id: number): Promise<ReleaseRecord> {
  return http.post<ReleaseRecord>(`/api/workflow/releases/${id}/publish`, undefined).then((res) => res.data)
}

export function rollbackRelease(id: number): Promise<ReleaseRecord> {
  return http.post<ReleaseRecord>(`/api/workflow/releases/${id}/rollback`, undefined).then((res) => res.data)
}
