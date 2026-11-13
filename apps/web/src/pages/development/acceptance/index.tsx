import { useEffect, useMemo, useState } from 'react'
import { Alert, Button, Card, Col, Empty, Progress, Row, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloudServerOutlined, DatabaseOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import { listDataSources, type DataSource } from '@/api/metadata'
import {
  AcceptanceContractError, acceptanceAtTime, getAcceptanceHistory, getCurrentAcceptance,
  type AcceptanceGateName, type AcceptanceState, type CurrentAcceptance, type HistoricalAcceptance,
} from '@/api/acceptance'
import { getFlinkHealth, getSparkHealth, listTasks, type FlinkGatewayHealth, type SparkRunnerHealth, type TaskView } from '@/api/workflow'
import { useSerializedPolling } from '@/hooks/useSerializedPolling'
import { effectiveConnectionStatus } from '@/pages/datasource/connectionHealth'

const milestones: { code: string; title: string; summary: string; gates: AcceptanceGateName[] }[] = [
  { code: 'R1', title: '实时湖仓兼容矩阵', summary: '跨引擎读写与空环境重建', gates: ['r1-empty-rebuild'] },
  { code: 'R2', title: 'Flink CDC 最小闭环', summary: '快照、增量、checkpoint 与重启恢复', gates: ['r2-cdc'] },
  { code: 'R3', title: '受管流任务生命周期', summary: '生命周期、失联对账及隔离 HA 运行证据', gates: ['r3-managed-lifecycle', 'r3-ha-runtime'] },
  { code: 'R4', title: 'Schema 演进与实时质量', summary: '事件时间、质量及控制面门禁', gates: ['r4-event-time-quality', 'r4-r5-control-plane'] },
  { code: 'R5', title: '湖仓表控制面', summary: '真实维护执行、结果回查与控制面门禁', gates: ['r5-maintenance-runner', 'r4-r5-control-plane'] },
]
interface AcceptanceSource { type: string; label: string; supplementary?: boolean }
const acceptanceSources: AcceptanceSource[] = [
  { type: 'postgresql', label: 'PostgreSQL' }, { type: 'mysql', label: 'MySQL' }, { type: 'mariadb', label: 'MariaDB' },
  { type: 'clickhouse', label: 'ClickHouse' }, { type: 'trino', label: 'Trino' }, { type: 'sqlite', label: 'SQLite' },
  { type: 'cockroachdb', label: 'CockroachDB' }, { type: 'h2', label: 'H2' }, { type: 'derby', label: 'Apache Derby' },
  { type: 'hive2', label: 'HiveServer2', supplementary: true }, { type: 'sqlserver', label: 'SQL Server', supplementary: true },
  { type: 'oracle', label: 'Oracle', supplementary: true },
]
const requiredSources = acceptanceSources.filter((item) => !item.supplementary)
const stateText: Record<AcceptanceState, string> = {
  verified: '当前冻结版本验收通过', failed: '当前验收未通过', missing: '当前验收证据缺失', expired: '当前验收证据已过期',
  mismatch: '当前验收证据与运行版本不一致', invalid: '当前验收证据无法校验', unavailable: '当前验收未验证',
}
function failureText(error: unknown): string {
  if (error instanceof AcceptanceContractError) return '验证响应不满足当前证据契约，不能判为通过。'
  const status = (error as { response?: { status?: number } } | null)?.response?.status
  if (status === 404) return '当前验收验证接口尚未接入，不能确认本版本通过。'
  if (status === 403) return '没有读取当前验收证据的权限。'
  if (status === 503) return '当前验收验证服务暂不可用，请稍后重试。'
  return '无法取得当前版本的完整验证结果，请稍后重试。'
}
function serviceLabel(status?: string) {
  return status === 'UP' ? { label: '在线', tone: 'success' as const } : { label: status || '未探测', tone: 'warning' as const }
}
function healthLabel(status?: string) {
  if (status === 'available') return { label: '可用', tone: 'success' as const }
  if (status === 'unavailable') return { label: '不可用', tone: 'danger' as const }
  return { label: status === 'checking' ? '探测中' : '未知', tone: 'warning' as const }
}

export default function AcceptanceProgressPage() {
  const [sources, setSources] = useState<DataSource[]>([]), [sourceReady, setSourceReady] = useState(false)
  const [tasks, setTasks] = useState<TaskView[]>([]), [tasksReady, setTasksReady] = useState(false)
  const [flink, setFlink] = useState<FlinkGatewayHealth | null>(null), [spark, setSpark] = useState<SparkRunnerHealth | null>(null)
  const [current, setCurrent] = useState<CurrentAcceptance | null>(null), [currentIssue, setCurrentIssue] = useState('正在核验当前版本。')
  const [history, setHistory] = useState<HistoricalAcceptance[]>([])
  const [loading, setLoading] = useState(false), [lastRefresh, setLastRefresh] = useState<Date | null>(null)
  const [errorCount, setErrorCount] = useState(0)
  const refresh = useSerializedPolling((signal) => {
    setLoading(true)
    return Promise.allSettled([listDataSources(), listTasks(signal), getFlinkHealth(signal), getSparkHealth(signal), getCurrentAcceptance(signal), getAcceptanceHistory(signal)])
  }, ([sourceResult, taskResult, flinkResult, sparkResult, currentResult, historyResult]) => {
    setSourceReady(sourceResult.status === 'fulfilled'); setSources(sourceResult.status === 'fulfilled' ? sourceResult.value : [])
    setTasksReady(taskResult.status === 'fulfilled'); setTasks(taskResult.status === 'fulfilled' ? taskResult.value : [])
    setFlink(flinkResult.status === 'fulfilled' ? flinkResult.value : null)
    setSpark(sparkResult.status === 'fulfilled' ? sparkResult.value : null)
    setCurrent(currentResult.status === 'fulfilled' ? acceptanceAtTime(currentResult.value) : null)
    setCurrentIssue(currentResult.status === 'fulfilled' ? '' : failureText(currentResult.reason))
    setHistory(historyResult.status === 'fulfilled' ? historyResult.value : [])
    setErrorCount([sourceResult, taskResult, flinkResult, sparkResult, currentResult, historyResult].filter((item) => item.status === 'rejected').length)
    setLastRefresh(new Date()); setLoading(false)
  }, 10000)

  useEffect(() => {
    if (current?.state !== 'verified') return
    const remaining = Date.parse(current.validUntilUtc) - Date.now()
    const expire = () => setCurrent((value) => value === current ? acceptanceAtTime(value) : value)
    if (remaining <= 0) { expire(); return }
    const timer = window.setTimeout(expire, remaining + 1)
    return () => window.clearTimeout(timer)
  }, [current])

  const effectiveCurrent = current ? acceptanceAtTime(current) : null
  const verified = effectiveCurrent?.state === 'verified' && effectiveCurrent.evidence !== null
  const evidence = verified ? effectiveCurrent.evidence : null
  const milestoneRows = milestones.map((milestone) => ({ ...milestone, passed: Boolean(evidence && milestone.gates.every((name) => evidence.gates.some((gate) => gate.name === name && gate.status === 'passed'))) }))
  const milestonePassed = milestoneRows.filter((item) => item.passed).length
  const sourceStats = useMemo(() => ({
    configured: requiredSources.filter((item) => sources.some((source) => source.type === item.type)).length,
    available: requiredSources.filter((item) => sources.some((source) => source.type === item.type && effectiveConnectionStatus(source.connectionHealth) === 'available')).length,
  }), [sources])
  const taskStats = useMemo(() => ({
    running: tasks.filter((view) => view.lastStatus === 'running' || view.lastStatus === 'submitting').length,
    unknown: tasks.filter((view) => view.lastStatus === 'unknown').length,
    failed: tasks.filter((view) => view.lastStatus === 'failed' || view.lastStatus === 'timed_out').length,
  }), [tasks])
  const sourceColumns: TableColumnsType<AcceptanceSource> = [
    { title: '数据源', dataIndex: 'label', key: 'label', render: (value: string, row) => <Space><DatabaseOutlined />{value}<Tag>{row.type}</Tag></Space> },
    { title: '当前配置', key: 'configured', render: (_, row) => !sourceReady ? '未知' : sources.some((source) => source.type === row.type) ? '已配置' : '未配置' },
    { title: '最近连接健康', key: 'health', render: (_, row) => { const meta = healthLabel(effectiveConnectionStatus(sources.find((source) => source.type === row.type)?.connectionHealth)); return <StatusBadge tone={meta.tone} dot={false}>{meta.label}</StatusBadge> } },
    { title: '当前运行证据', key: 'runtime', render: (_, row) => row.supplementary ? <Tag>补充环境待核验</Tag> : evidence?.datasourceResults.some((item) => item.type === row.type && item.status === 'passed') ? <Tag color="success">当前通过</Tag> : <Tag>未验证</Tag> },
    { title: '范围', key: 'scope', render: (_, row) => row.supplementary ? '需对应真实环境的独立证据' : '连接健康不能替代业务运行证据' },
  ]
  const milestoneColumns: TableColumnsType<typeof milestoneRows[number]> = [
    { title: '阶段', dataIndex: 'code', key: 'code', width: 70 }, { title: '能力', dataIndex: 'title', key: 'title', width: 190 },
    { title: '当前结果', key: 'status', width: 110, render: (_, row) => <StatusBadge tone={row.passed ? 'success' : 'warning'} dot={false}>{row.passed ? '已通过' : '未验证'}</StatusBadge> },
    { title: '验证内容', dataIndex: 'summary', key: 'summary' },
  ]
  const state = effectiveCurrent?.state ?? 'unavailable'
  const description = verified && effectiveCurrent?.currentBinding
    ? `运行 ${effectiveCurrent.currentBinding.runId} · 源码 ${effectiveCurrent.currentBinding.source.commit.slice(0, 12)} · 迁移 ${effectiveCurrent.currentBinding.migrationCount} 份；有效至 ${new Date(effectiveCurrent.validUntilUtc).toLocaleString()}`
    : currentIssue || '缺失、失败、过期或不匹配的证据均不能计作当前通过。请查看服务端验证结果并重试。'

  return <div className="workflow-list-page">
    <PageHeader description="当前验收由服务端核对冻结版本及完整证据；历史记录单独展示，不计作当前通过。每 10 秒串行刷新。"
      extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>} />
    <Alert className="form-section-space" showIcon type={verified ? 'success' : state === 'failed' || state === 'mismatch' || state === 'invalid' ? 'error' : 'warning'} message={stateText[state]} description={description} />
    {errorCount > 0 && <Alert className="form-section-space" showIcon type="warning" message="部分接口暂时不可用，未取得的新状态保持未知" />}
    <Row gutter={12} className="stat-row">
      <Col xs={12} lg={6}>
        <StatCard
          title="R1-R5 当前验收"
          value={`${milestonePassed} / 5`}
          icon={<CheckCircleOutlined />}
          tone={verified ? 'green' : 'neutral'}
          footer={<Progress percent={milestonePassed * 20} status={verified ? 'success' : 'normal'} showInfo={false} size="small" style={{ margin: 0 }} />}
        />
      </Col>
      <Col xs={12} lg={6}>
        <StatCard
          title="数据源当前可探测"
          value={`${sourceReady ? sourceStats.available : '—'} / 9`}
          icon={<DatabaseOutlined />}
          footer="仅为连接健康"
        />
      </Col>
      <Col xs={12} lg={6}>
        <StatCard
          title="流任务运行中"
          value={String(tasksReady ? taskStats.running : '—')}
          icon={<CloudServerOutlined />}
          footer={tasksReady ? `未知 ${taskStats.unknown} · 失败 ${taskStats.failed}` : '运行状态暂不可用'}
        />
      </Col>
      <Col xs={12} lg={6}>
        <StatCard
          title="最近刷新"
          value={lastRefresh?.toLocaleTimeString() ?? '—'}
          icon={<ReloadOutlined />}
        />
      </Col>
    </Row>
    <Row gutter={[16, 16]} className="form-section-space">
      <Col xs={24} md={12}><Card title="Flink SQL Gateway" size="small"><StatusBadge tone={serviceLabel(flink?.status).tone} dot={false}>{serviceLabel(flink?.status).label}</StatusBadge></Card></Col>
      <Col xs={24} md={12}><Card title="Spark Runner" size="small"><StatusBadge tone={serviceLabel(spark?.status).tone} dot={false}>{serviceLabel(spark?.status).label}</StatusBadge></Card></Col>
    </Row>
    <Alert className="form-section-space" showIcon type={verified ? 'success' : 'warning'}
      message={verified ? '九类必需数据源的当前业务运行证据已通过' : '九类必需数据源尚未取得当前完整验证'}
      description={verified ? '服务端已核对同一上下文中的具体数据源断言和必要附件；不使用连接探测或历史摘要替代。' : '历史成功、HTTP 200、服务在线或数据源可连接，均不能作为本版本已通过的结论。'} />
    <DataSurface label="R1-R5 当前阶段进度"><Table rowKey="code" size="small" columns={milestoneColumns} dataSource={milestoneRows} pagination={false} scroll={{ x: 750 }} /></DataSurface>
    <DataSurface label={`九类必需 + 三类补充 · 已配置 ${sourceReady ? sourceStats.configured : '未知'} / 9 必需`}><Table rowKey="type" size="small" columns={sourceColumns} dataSource={acceptanceSources} pagination={false} scroll={{ x: 900 }} /></DataSurface>
    <DataSurface title="历史运行记录（不计当前验收）" description="旧 schema1/schema2 摘要仅供追溯。它们没有建立当前运行版本与完整附件的验证关系。">
      {history.length === 0 ? <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="暂无可读取的历史摘要" /> : history.map((item) => <Card key={item.kind} size="small" className="form-section-space">
        <Space direction="vertical" size={4}><Typography.Text strong>{item.kind === 'platform' ? '平台验收历史' : 'Schema/质量控制面历史'}</Typography.Text>
          <Tag>历史结果：{item.status === 'passed' ? '通过' : item.status === 'failed' ? '失败' : '未完成'}（未作当前核验）</Tag>
          <Typography.Text type="secondary">生成于 {new Date(item.generatedAtUtc).toLocaleString()} · schema {item.schemaVersion}</Typography.Text>
          <Typography.Text code>{item.source}</Typography.Text></Space>
      </Card>)}
    </DataSurface>
  </div>
}
