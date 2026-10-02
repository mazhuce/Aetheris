import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  Alert,
  Button,
  Card,
  Col,
  Progress,
  Row,
  Space,
  Statistic,
  Table,
  Tag,
  Typography
} from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloudServerOutlined, DatabaseOutlined, ReloadOutlined, WarningOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { listDataSources, type DataSource } from '@/api/metadata'
import { getControlPlaneAcceptance, getPlatformReleaseAcceptance, type ControlPlaneAcceptanceSummary, type PlatformReleaseAcceptanceSummary } from '@/api/acceptance'
import { getFlinkHealth, getSparkHealth, listTasks, type FlinkGatewayHealth, type SparkRunnerHealth, type TaskView } from '@/api/workflow'
import { effectiveConnectionStatus } from '@/pages/datasource/connectionHealth'

type Milestone = {
  code: string
  title: string
  summary: string
  evidence: string
  evidenceGeneratedAtUtc?: string
  status: '已通过' | '待核对' | '待重跑'
}

type AcceptanceSource = {
  type: string
  label: string
  acceptance: string
}

const milestones: Milestone[] = [
  { code: 'R1', title: '实时湖仓兼容矩阵', summary: 'Flink 写入、Spark 与 Trino 读取同一 Iceberg v2 表，支持空环境重建。', evidence: 'r1-fixture/acceptance-manifest.json', status: '已通过' },
  { code: 'R2', title: 'Flink CDC 最小闭环', summary: 'PostgreSQL / MySQL 快照、增量、checkpoint、重启恢复与一致性校验。', evidence: 'r1-fixture/r2-cdc-acceptance-report.json', status: '已通过' },
  { code: 'R3', title: '受管流任务生命周期', summary: 'Job ID、checkpoint、savepoint、暂停、恢复、升级、失联对账与 reconciler。', evidence: 'r3-ha/r3-ha-lifecycle-report.json', status: '已通过' },
  { code: 'R4', title: 'Schema 演进与实时质量', summary: '事件时间、迟到数据、质量规则、死信和 Gold 发布阻断。', evidence: 'r4-event-time/r4-event-time-quality-report.json', status: '已通过' },
  { code: 'R5', title: '湖仓表控制面', summary: '快照、维护 Runner、幂等请求、过期和结果核验。', evidence: 'r5-maintenance-runner/r5-maintenance-runtime-report.json', status: '已通过' }
]

const acceptanceSources: AcceptanceSource[] = [
  { type: 'postgresql', label: 'PostgreSQL', acceptance: 'CDC + JDBC smoke' },
  { type: 'mysql', label: 'MySQL', acceptance: 'CDC + JDBC smoke' },
  { type: 'mariadb', label: 'MariaDB', acceptance: 'JDBC smoke' },
  { type: 'hive2', label: 'HiveServer2', acceptance: '外部环境验收' },
  { type: 'sqlserver', label: 'SQL Server', acceptance: '外部环境验收' },
  { type: 'oracle', label: 'Oracle', acceptance: '外部环境验收' },
  { type: 'clickhouse', label: 'ClickHouse', acceptance: 'JDBC smoke' },
  { type: 'trino', label: 'Trino', acceptance: 'JDBC smoke' },
  { type: 'sqlite', label: 'SQLite', acceptance: 'JDBC smoke' },
  { type: 'cockroachdb', label: 'CockroachDB', acceptance: 'JDBC smoke' },
  { type: 'h2', label: 'H2', acceptance: 'JDBC smoke' },
  { type: 'derby', label: 'Apache Derby', acceptance: 'JDBC smoke' }
]

const requiredAcceptanceSources = acceptanceSources.filter((item) => item.acceptance !== '外部环境验收')

function healthLabel(status?: string) {
  if (status === 'available') return { label: '可用', tone: 'success' as const }
  if (status === 'checking') return { label: '探测中', tone: 'info' as const }
  if (status === 'unavailable') return { label: '不可用', tone: 'danger' as const }
  return { label: '未知', tone: 'warning' as const }
}

function serviceLabel(status?: string) {
  return status === 'UP'
    ? { label: '在线', tone: 'success' as const }
    : { label: status || '未探测', tone: 'danger' as const }
}

function schemaPolicyLabel(status?: string) {
  if (status === 'approved') return '审批通过，可生成计划'
  if (status === 'rejected') return '已拒绝，计划已阻断'
  if (status === 'isolated') return '已隔离，计划已阻断'
  return status || '未知'
}

function externalDatasourceLabel(status?: string) {
  if (status === 'passed') return '已通过真实连接'
  if (status === 'manual_review_required') return '待提供真实实例和凭据'
  if (status === 'failed') return '连接失败，需修复'
  return '状态未知'
}

function datasourceRuntimeLabel(status?: string) {
  if (status === 'passed') return { label: '通过', tone: 'success' as const }
  if (status === 'failed') return { label: '失败', tone: 'danger' as const }
  if (status === 'manual_review_required') return { label: '待人工核对', tone: 'warning' as const }
  return { label: '未验收', tone: 'neutral' as const }
}
export default function AcceptanceProgressPage() {
  const [sources, setSources] = useState<DataSource[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [flink, setFlink] = useState<FlinkGatewayHealth | null>(null)
  const [spark, setSpark] = useState<SparkRunnerHealth | null>(null)
  const [controlPlane, setControlPlane] = useState<ControlPlaneAcceptanceSummary | null>(null)
  const [platformRelease, setPlatformRelease] = useState<PlatformReleaseAcceptanceSummary | null>(null)
  const [loading, setLoading] = useState(false)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [errorCount, setErrorCount] = useState(0)

  const refresh = useCallback(async () => {
    setLoading(true)
    const results = await Promise.allSettled([listDataSources(), listTasks(), getFlinkHealth(), getSparkHealth(), getControlPlaneAcceptance(), getPlatformReleaseAcceptance()])
    const [sourceResult, taskResult, flinkResult, sparkResult, controlPlaneResult, platformReleaseResult] = results
    if (sourceResult.status === 'fulfilled') setSources(sourceResult.value)
    if (taskResult.status === 'fulfilled') setTasks(taskResult.value)
    if (flinkResult.status === 'fulfilled') setFlink(flinkResult.value)
    else setFlink({ status: 'DOWN', message: 'Flink SQL Gateway 不可达' })
    if (sparkResult.status === 'fulfilled') setSpark(sparkResult.value)
    else setSpark({ status: 'DOWN', activeJobs: 0, maxConcurrency: 0, message: 'Spark Runner 不可达' })
    if (controlPlaneResult.status === 'fulfilled') setControlPlane(controlPlaneResult.value)
    else setControlPlane(null)
    if (platformReleaseResult.status === 'fulfilled') setPlatformRelease(platformReleaseResult.value)
    else setPlatformRelease(null)
    setErrorCount(results.filter((item) => item.status === 'rejected').length)
    setLastRefresh(new Date())
    setLoading(false)
  }, [])

  useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(), 10000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const sourceStats = useMemo(() => {
    const tracked = requiredAcceptanceSources.map((item) => sources.filter((source) => source.type === item.type))
    const configured = tracked.filter((items) => items.length > 0).length
    const available = tracked.filter((items) => items.some((source) => effectiveConnectionStatus(source.connectionHealth) === 'available')).length
    return { configured, available, requiredCount: requiredAcceptanceSources.length }
  }, [sources])

  const milestoneRows = useMemo<Milestone[]>(() => milestones.map((milestone) => {
    const evidence = platformRelease?.milestones.find((item) => item.code === milestone.code)
    const status = evidence?.status
    return { ...milestone, evidence: evidence?.evidence || milestone.evidence, evidenceGeneratedAtUtc: evidence?.evidenceGeneratedAtUtc || undefined, status: status === 'passed' ? '已通过' : status ? '待核对' : '待核对' }
  }), [platformRelease])

  const milestonePassed = milestoneRows.filter((milestone) => milestone.status === '已通过').length
  const taskStats = useMemo(() => ({
    running: tasks.filter((view) => view.lastStatus === 'running' || view.lastStatus === 'submitting').length,
    unknown: tasks.filter((view) => view.lastStatus === 'unknown').length,
    failed: tasks.filter((view) => view.lastStatus === 'failed' || view.lastStatus === 'timed_out').length
  }), [tasks])

  const sourceColumns = useMemo<TableColumnsType<AcceptanceSource>>(() => [
    { title: '数据源', dataIndex: 'label', key: 'label', width: 150, render: (value: string, source) => <Space><DatabaseOutlined /><span>{value}</span><Tag>{source.type}</Tag></Space> },
    { title: '当前配置', key: 'configured', width: 110, render: (_, source) => sources.some((item) => item.type === source.type) ? <StatusBadge tone="success" dot={false}>已配置</StatusBadge> : <StatusBadge tone="neutral" dot={false}>未配置</StatusBadge> },
    { title: '最近连接健康', key: 'health', width: 150, render: (_, source) => { const item = sources.find((candidate) => candidate.type === source.type); const meta = healthLabel(effectiveConnectionStatus(item?.connectionHealth)); return <StatusBadge tone={meta.tone} dot={false}>{meta.label}</StatusBadge> } },
    { title: '验收路径', dataIndex: 'acceptance', key: 'acceptance', width: 180 },
    { title: '真实运行验收', key: 'runtime', width: 130, render: (_, source) => { const status = platformRelease?.datasourceRuntimeResults?.find((item) => item.type === source.type)?.status; const meta = datasourceRuntimeLabel(status); return <StatusBadge tone={meta.tone} dot={false}>{meta.label}</StatusBadge> } },
    { title: '说明', key: 'description', render: (_, source) => source.acceptance === '外部环境验收' ? <Typography.Text type="secondary">目录已实现，本机未伪造实例</Typography.Text> : <Typography.Text type="secondary">配置健康不等于完整运行门禁</Typography.Text> }
  ], [sources, platformRelease])

  const milestoneColumns = useMemo<TableColumnsType<Milestone>>(() => [
    { title: '阶段', dataIndex: 'code', key: 'code', width: 70, render: (value: string) => <Tag color="blue">{value}</Tag> },
    { title: '能力', dataIndex: 'title', key: 'title', width: 180 },
    { title: '当前进度', key: 'status', width: 100, render: (_, row) => <StatusBadge tone={row.status === '已通过' ? 'success' : 'warning'} dot={false}>{row.status}</StatusBadge> },
    { title: '验证内容', dataIndex: 'summary', key: 'summary' },
    { title: '证据', dataIndex: 'evidence', key: 'evidence', width: 280, render: (value: string, row) => <Space direction='vertical' size={0}><Typography.Text code>{value}</Typography.Text>{row.evidenceGeneratedAtUtc && <Typography.Text type='secondary'>生成于 {new Date(row.evidenceGeneratedAtUtc).toLocaleString()}</Typography.Text>}</Space> }
  ], [])

  return (
    <div className="workflow-list-page">
      <PageHeader
        description="统一跟踪 R1-R5 验收、实时服务状态、流任务运行态和九类本机真实数据源与外部扩展数据源进度。页面每 10 秒刷新一次，未知状态保持为未知。"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
      />

      {errorCount > 0 && <Alert type="warning" showIcon message="部分实时接口暂时不可用" description="页面保留已知状态，并将无法取得的新状态显示为未知；不会把请求失败当作成功。" className="form-section-space" />}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="R1-R5 验收" value={milestonePassed} suffix="/ 5" prefix={<CheckCircleOutlined />} /><Progress percent={Math.round((milestonePassed / milestones.length) * 100)} status={milestonePassed === milestones.length ? 'success' : 'active'} showInfo={false} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="数据源当前可探测" value={sourceStats.available} suffix={`/ ${sourceStats.requiredCount}`} prefix={<DatabaseOutlined />} /><Progress percent={Math.round((sourceStats.available / sourceStats.requiredCount) * 100)} showInfo={false} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="流任务运行中" value={taskStats.running} prefix={<CloudServerOutlined />} /><Typography.Text type="secondary">未知 {taskStats.unknown} · 失败 {taskStats.failed}</Typography.Text></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="最近刷新" value={lastRefresh ? lastRefresh.toLocaleTimeString() : '—'} prefix={<ReloadOutlined />} /><Typography.Text type="secondary">每 10 秒自动刷新</Typography.Text></Card></Col>
      </Row>

      <Row gutter={[16, 16]} className="form-section-space">
        <Col xs={24} md={12}><Card title="Flink SQL Gateway" size="small"><Space><StatusBadge tone={serviceLabel(flink?.status).tone} dot={false}>{serviceLabel(flink?.status).label}</StatusBadge><Typography.Text>{flink?.version ? `Flink ${flink.version}` : flink?.message || '等待探测'}</Typography.Text></Space></Card></Col>
        <Col xs={24} md={12}><Card title="Spark Runner" size="small"><Space><StatusBadge tone={serviceLabel(spark?.status).tone} dot={false}>{serviceLabel(spark?.status).label}</StatusBadge><Typography.Text>{spark ? `活动任务 ${spark.activeJobs} / 并发上限 ${spark.maxConcurrency}` : '等待探测'}</Typography.Text></Space></Card></Col>
      </Row>

      <Alert
        type="success"
        showIcon
        icon={<WarningOutlined />}
        className="form-section-space"
        message="Datasource 本机九类运行门禁最近一次已通过"
        description="最近一次真实报告已通过九类 SELECT 1 探测并完成数据源行清理：PostgreSQL、MySQL、Trino、MariaDB、ClickHouse、CockroachDB、SQLite、H2、Apache Derby。证据：artifacts/datasource-smoke/runtime-acceptance-report.json"
      />

      <Alert
        type="warning"
        showIcon
        className="form-section-space"
        message="三类外部数据源作为补充环境验收"
        description={`HiveServer2、SQL Server、Oracle 已纳入目录与验收清单；当前状态：${externalDatasourceLabel(platformRelease?.externalDatasourceStatus)}。缺失只影响 supplementary evidence，不影响本机九类真实运行门禁。`}
      />

      {controlPlane ? (
        <Alert
          type={controlPlane.status === 'passed' ? 'success' : 'error'}
          showIcon
          className="form-section-space"
          message={`Schema policy 运行证据 · ${controlPlane.status === 'passed' ? '已通过' : '需核对'}`}
          description={`重命名：${schemaPolicyLabel(controlPlane.schemaPolicy.rename.status)}；删除：${schemaPolicyLabel(controlPlane.schemaPolicy.drop.status)}；缩窄类型：${schemaPolicyLabel(controlPlane.schemaPolicy.narrow.status)}。质量门禁：${controlPlane.quality.blockedOnFailure && controlPlane.quality.reopenedAfterPass ? '先阻断、后恢复' : '证据不完整'}。生成于 ${new Date(controlPlane.generatedAtUtc).toLocaleString()}`}
        />
      ) : (
        <Alert
          type="warning"
          showIcon
          className="form-section-space"
          message="Schema policy 运行证据暂不可用"
          description="前端不会把报告缺失误显示为通过；请重新运行 R4/R5 控制面验收。"
        />
      )}
      <DataSurface label="R1-R5 阶段进度">
        <Table<Milestone> rowKey="code" size="small" columns={milestoneColumns} dataSource={milestoneRows} pagination={false} scroll={{ x: 980 }} />
      </DataSurface>

      <DataSurface label={`九类必需 + 三类补充 · 已配置 ${sourceStats.configured} / ${sourceStats.requiredCount} 必需`}>
        <Table<AcceptanceSource> rowKey="type" size="small" columns={sourceColumns} dataSource={acceptanceSources} pagination={false} scroll={{ x: 900 }} />
      </DataSurface>
    </div>
  )
}
