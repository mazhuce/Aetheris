import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Popconfirm, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import {
  getQualitySummary,
  listAllResultsPage,
  listQualityQuarantinePage,
  listRulesPage,
  releaseQualityQuarantine,
  type QualityCheckResult,
  type QualityQuarantineRecord,
  type QualitySummary
} from '@/api/quality'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'
import ExecutionReconciliationModal from '@/components/ExecutionReconciliationModal'
import { useVisibleListPage } from '@/hooks/useVisibleListPage'

const statusMeta: Record<string, { label: string; color: string }> = {
  passed: { label: '通过', color: 'success' },
  failed: { label: '未通过', color: 'error' },
  error: { label: '异常', color: 'warning' },
  running: { label: '执行中', color: 'processing' },
  unknown: { label: '待对账', color: 'warning' }
}

export default function QualityTrendPage() {
  const { message } = App.useApp()
  const [summary, setSummary] = useState<QualitySummary>()
  const [quarantineStatus, setQuarantineStatus] = useState('open')
  const [quarantineLoading, setQuarantineLoading] = useState(false)
  const [status, setStatus] = useState<string>()
  const [ruleId, setRuleId] = useState<number>()
  const [reconcileId, setReconcileId] = useState<number>()
  const canManage = canManagePlatform()
  const resultListing = useVisibleListPage(
    (cursor, signal) => listAllResultsPage(status, ruleId, { cursor, size: 50, signal }),
    JSON.stringify({ status, ruleId })
  )
  const rulesListing = useVisibleListPage(
    (cursor, signal) => listRulesPage({ cursor, size: 100, signal }), 'quality-rules'
  )
  const quarantineListing = useVisibleListPage(
    (cursor, signal) => listQualityQuarantinePage(quarantineStatus, { cursor, size: 50, signal }),
    JSON.stringify({ quarantineStatus })
  )
  const results = resultListing.items
  const rules = rulesListing.items
  const quarantine = quarantineListing.items
  const loading = resultListing.loading || rulesListing.loading

  const refresh = useCallback(async () => {
    resultListing.refresh()
    rulesListing.refresh()
    quarantineListing.refresh()
    try { setSummary(await getQualitySummary()) } catch { message.error('质量检查概览加载失败') }
  }, [message, resultListing, rulesListing, quarantineListing])

  useEffect(() => { void getQualitySummary().then(setSummary).catch(() => message.error('质量检查概览加载失败')) }, [message, status, ruleId])

  const ruleName = useCallback(
    (id: number) => rules.find((rule) => rule.id === id)?.name ?? `规则 ${id}`,
    [rules]
  )

  const release = async (id: number) => {
    setQuarantineLoading(true)
    try {
      await releaseQualityQuarantine(id)
      message.success('隔离记录已释放')
      quarantineListing.refresh()
    } catch {
      message.error('隔离记录释放失败')
    } finally {
      setQuarantineLoading(false)
    }
  }

  const columns: TableColumnsType<QualityCheckResult> = [
    {
      title: '规则',
      dataIndex: 'ruleId',
      key: 'ruleId',
      width: 220,
      render: (value: number) => ruleName(value)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: QualityCheckResult['status']) => {
        const meta = statusMeta[value] ?? { label: value, color: 'default' }
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '违规数',
      dataIndex: 'violationCount',
      key: 'violationCount',
      width: 90,
      render: (value?: number, record?: QualityCheckResult) =>
        value === undefined || value === null
          ? '—'
          : `${value}（阈值 ${record?.thresholdValue ?? 0}）`
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 90,
      render: (value?: number) => (value === undefined || value === null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(1)} s` : `${value} ms`)
    },
    { title: '触发人', dataIndex: 'triggeredBy', key: 'triggeredBy', width: 110, render: (value?: string) => value || '—' },
    { title: '检查时间', dataIndex: 'startedAt', key: 'startedAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '说明',
      dataIndex: 'message',
      key: 'message',
      ellipsis: true,
      render: (value?: string) => value || '—'
    }
  ]
  if (canManage) columns.push({ title: '处理', key: 'reconcile', width: 90,
    render: (_, result) => result.status === 'unknown'
      ? <Button type="link" size="small" onClick={() => setReconcileId(result.id)}>核实结果</Button> : '—'
  })
  if (results.some((result) => result.failureCode)) columns.push({
    title: '失败诊断',
    dataIndex: 'failureCode',
    key: 'failureCode',
    width: 170,
    render: (value?: string) => value || '—'
  })

  const quarantineColumns: TableColumnsType<QualityQuarantineRecord> = [
    { title: '目标表', dataIndex: 'targetTable', key: 'targetTable', width: 220 },
    { title: '违规数', dataIndex: 'violationCount', key: 'violationCount', width: 90 },
    { title: '阈值', dataIndex: 'thresholdValue', key: 'thresholdValue', width: 80 },
    { title: '原因', dataIndex: 'reason', key: 'reason', ellipsis: true },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value: string) => formatDateTime(value) },
    {
      title: '状态', dataIndex: 'status', key: 'status', width: 90,
      render: (value: QualityQuarantineRecord['status']) => <Tag color={value === 'open' ? 'error' : value === 'released' ? 'success' : 'default'}>{value === 'open' ? '待处理' : value === 'released' ? '已释放' : '已丢弃'}</Tag>
    }
  ]
  if (canManage) quarantineColumns.push({
    title: '操作', key: 'action', width: 100,
    render: (_, record) => record.status === 'open' ? (
      <Popconfirm title="释放这条隔离记录？" onConfirm={() => void release(record.id)}>
        <Button type="link" size="small" loading={quarantineLoading}>释放</Button>
      </Popconfirm>
    ) : '—'
  })

  const scoreText = summary && summary.score >= 0 ? `${summary.score}%` : '暂无数据'

  return (
    <div className="quality-trend-page">
      <ExecutionReconciliationModal kind="quality" id={reconcileId} onClose={() => setReconcileId(undefined)} onResolved={refresh} />
      <PageHeader
        description="数据质量运行趋势：基于最近一次检查的规则通过情况与历史检查记录，识别持续未通过的目标表。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新数据</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}><StatCard title="质量分" value={scoreText} icon={<SafetyOutlined />} footer="最近一次检查通过率" tone="green" /></Col>
        <Col xs={12} lg={6}><StatCard title="通过规则" value={String(summary?.passed ?? 0)} icon={<CheckCircleOutlined />} footer={`${summary?.checked ?? 0} 条已检查`} tone="green" /></Col>
        <Col xs={12} lg={6}><StatCard title="未通过" value={String(summary?.failed ?? 0)} icon={<ExclamationCircleOutlined />} footer="违规数超过阈值" tone="orange" /></Col>
        <Col xs={12} lg={6}><StatCard title="执行异常" value={String(summary?.errored ?? 0)} icon={<CloseCircleOutlined />} footer="检查本身失败" tone="orange" /></Col>
      </Row>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>规则</span>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="全部规则"
              allowClear
              value={ruleId}
              onChange={setRuleId}
              className="filter-control-sm"
              options={rules.map((rule) => ({ value: rule.id, label: rule.name }))}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              allowClear
              value={status}
              onChange={setStatus}
              className="filter-control-sm"
              options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
        </div>
      </PageToolbar>

      <DataSurface title="检查历史" description="最近 100 次质量检查" label="检查记录明细">
        <Table<QualityCheckResult>
          rowKey="id"
          columns={columns}
          dataSource={results}
          loading={loading}
          scroll={{ x: 1100 }}
          pagination={false}
          locale={{ emptyText: <Typography.Text type="secondary">{resultListing.error ?? (resultListing.hasMore ? '暂未找到可见记录，可继续查看' : '暂无检查记录。在数据质量页创建并运行规则，或配置定时调度后，历史会出现在这里。')}</Typography.Text> }}
        />
        <div className="table-pagination-bar">
          <Button disabled={!resultListing.canPrevious || resultListing.loading} onClick={resultListing.previous}>上一页</Button>
          <span>第 {resultListing.pageNumber} 页 · 本页 {results.length} 条</span>
          <Button disabled={!resultListing.hasMore || resultListing.loading || !!resultListing.error} onClick={resultListing.next}>下一页</Button>
        </div>
      </DataSurface>

      <DataSurface title="质量隔离区" description="质量未通过的运行记录；释放动作会保留处理人和时间" label="质量隔离记录">
        <PageToolbar>
          <div className="filter-bar">
            <div className="filter-item">
              <span>状态</span>
              <Select
                value={quarantineStatus}
                onChange={setQuarantineStatus}
                className="filter-control-sm"
                options={[{ value: 'open', label: '待处理' }, { value: 'released', label: '已释放' }, { value: 'discarded', label: '已丢弃' }]}
              />
            </div>
          </div>
        </PageToolbar>
        <Table<QualityQuarantineRecord>
          rowKey="id"
          columns={quarantineColumns}
          dataSource={quarantine}
          loading={quarantineLoading || loading}
          scroll={{ x: 1000 }}
          pagination={false}
          locale={{ emptyText: <Typography.Text type="secondary">{quarantineListing.error ?? (quarantineListing.hasMore ? '暂未找到可见记录，可继续查看' : '当前状态没有隔离记录。')}</Typography.Text> }}
        />
        <div className="table-pagination-bar">
          <Button disabled={!quarantineListing.canPrevious || quarantineListing.loading} onClick={quarantineListing.previous}>上一页</Button>
          <span>第 {quarantineListing.pageNumber} 页 · 本页 {quarantine.length} 条</span>
          <Button disabled={!quarantineListing.hasMore || quarantineListing.loading || !!quarantineListing.error} onClick={quarantineListing.next}>下一页</Button>
        </div>
      </DataSurface>
    </div>
  )
}
