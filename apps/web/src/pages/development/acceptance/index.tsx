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
import { getFlinkHealth, getSparkHealth, listTasks, type FlinkGatewayHealth, type SparkRunnerHealth, type TaskView } from '@/api/workflow'
import { effectiveConnectionStatus } from '@/pages/datasource/connectionHealth'

type Milestone = {
  code: string
  title: string
  summary: string
  evidence: string
  status: '已通过' | '待重跑'
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
  { type: 'sqlite', label: 'SQLite', acceptance: 'JDBC smoke' }
]

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

export default function AcceptanceProgressPage() {
  const [sources, setSources] = useState<DataSource[]>([])
  const [tasks, setTasks] = useState<TaskView[]>([])
  const [flink, setFlink] = useState<FlinkGatewayHealth | null>(null)
  const [spark, setSpark] = useState<SparkRunnerHealth | null>(null)
  const [loading, setLoading] = useState(false)
  const [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [errorCount, setErrorCount] = useState(0)

  const refresh = useCallback(async () => {
    setLoading(true)
    const results = await Promise.allSettled([listDataSources(), listTasks(), getFlinkHealth(), getSparkHealth()])
    const [sourceResult, taskResult, flinkResult, sparkResult] = results
    if (sourceResult.status === 'fulfilled') setSources(sourceResult.value)
    if (taskResult.status === 'fulfilled') setTasks(taskResult.value)
    if (flinkResult.status === 'fulfilled') setFlink(flinkResult.value)
    else setFlink({ status: 'DOWN', message: 'Flink SQL Gateway 不可达' })
    if (sparkResult.status === 'fulfilled') setSpark(sparkResult.value)
    else setSpark({ status: 'DOWN', activeJobs: 0, maxConcurrency: 0, message: 'Spark Runner 不可达' })
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
    const tracked = acceptanceSources.map((item) => sources.filter((source) => source.type === item.type))
    const configured = tracked.filter((items) => items.length > 0).length
    const available = tracked.filter((items) => items.some((source) => effectiveConnectionStatus(source.connectionHealth) === 'available')).length
    return { configured, available }
  }, [sources])

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
    { title: '说明', key: 'description', render: (_, source) => source.acceptance === '外部环境验收' ? <Typography.Text type="secondary">目录已实现，本机未伪造实例</Typography.Text> : <Typography.Text type="secondary">配置健康不等于完整运行门禁</Typography.Text> }
  ], [sources])

  const milestoneColumns = useMemo<TableColumnsType<Milestone>>(() => [
    { title: '阶段', dataIndex: 'code', key: 'code', width: 70, render: (value: string) => <Tag color="blue">{value}</Tag> },
    { title: '能力', dataIndex: 'title', key: 'title', width: 180 },
    { title: '当前进度', key: 'status', width: 100, render: (_, row) => <StatusBadge tone={row.status === '已通过' ? 'success' : 'warning'} dot={false}>{row.status}</StatusBadge> },
    { title: '验证内容', dataIndex: 'summary', key: 'summary' },
    { title: '证据', dataIndex: 'evidence', key: 'evidence', width: 240, render: (value: string) => <Typography.Text code>{value}</Typography.Text> }
  ], [])

  return (
    <div className="workflow-list-page">
      <PageHeader
        description="统一跟踪 R1-R5 验收、实时服务状态、流任务运行态和 9 类数据源接入进度。页面每 10 秒刷新一次，未知状态保持为未知。"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
      />

      {errorCount > 0 && <Alert type="warning" showIcon message="部分实时接口暂时不可用" description="页面保留已知状态，并将无法取得的新状态显示为未知；不会把请求失败当作成功。" className="form-section-space" />}
      <Row gutter={[16, 16]}>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="R1-R5 验收" value={5} suffix="/ 5" prefix={<CheckCircleOutlined />} /><Progress percent={100} status="success" showInfo={false} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="数据源当前可探测" value={sourceStats.available} suffix={`/ ${acceptanceSources.length}`} prefix={<DatabaseOutlined />} /><Progress percent={Math.round((sourceStats.available / acceptanceSources.length) * 100)} showInfo={false} /></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="流任务运行中" value={taskStats.running} prefix={<CloudServerOutlined />} /><Typography.Text type="secondary">未知 {taskStats.unknown} · 失败 {taskStats.failed}</Typography.Text></Card></Col>
        <Col xs={24} sm={12} lg={6}><Card><Statistic title="最近刷新" value={lastRefresh ? lastRefresh.toLocaleTimeString() : '—'} prefix={<ReloadOutlined />} /><Typography.Text type="secondary">每 10 秒自动刷新</Typography.Text></Card></Col>
      </Row>

      <Row gutter={[16, 16]} className="form-section-space">
        <Col xs={24} md={12}><Card title="Flink SQL Gateway" size="small"><Space><StatusBadge tone={serviceLabel(flink?.status).tone} dot={false}>{serviceLabel(flink?.status).label}</StatusBadge><Typography.Text>{flink?.version ? `Flink ${flink.version}` : flink?.message || '等待探测'}</Typography.Text></Space></Card></Col>
        <Col xs={24} md={12}><Card title="Spark Runner" size="small"><Space><StatusBadge tone={serviceLabel(spark?.status).tone} dot={false}>{serviceLabel(spark?.status).label}</StatusBadge><Typography.Text>{spark ? `活动任务 ${spark.activeJobs} / 并发上限 ${spark.maxConcurrency}` : '等待探测'}</Typography.Text></Space></Card></Col>
      </Row>

      <Alert
        type="warning"
        showIcon
        icon={<WarningOutlined />}
        className="form-section-space"
        message="Datasource 七类运行门禁当前待重跑"
        description="最近一次报告因为 Docker Desktop Linux Engine API 500 失败。这里展示的是当前配置和连接健康，不把目录存在、配置保存或单次连接成功误判为完整 smoke 通过。"
      />

      <DataSurface label="R1-R5 阶段进度">
        <Table<Milestone> rowKey="code" size="small" columns={milestoneColumns} dataSource={milestones} pagination={false} scroll={{ x: 980 }} />
      </DataSurface>

      <DataSurface label={`9 类数据源跟踪 · 已配置 ${sourceStats.configured} / ${acceptanceSources.length}`}>
        <Table<AcceptanceSource> rowKey="type" size="small" columns={sourceColumns} dataSource={acceptanceSources} pagination={false} scroll={{ x: 900 }} />
      </DataSurface>
    </div>
  )
}
