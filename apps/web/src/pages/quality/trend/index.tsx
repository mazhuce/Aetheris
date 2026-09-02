import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloseCircleOutlined, ExclamationCircleOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import {
  getQualitySummary,
  listAllResults,
  listRules,
  type QualityCheckResult,
  type QualityRule,
  type QualitySummary
} from '@/api/quality'
import { formatDateTime } from '@/utils/format'

const statusMeta: Record<string, { label: string; color: string }> = {
  passed: { label: '通过', color: 'success' },
  failed: { label: '未通过', color: 'error' },
  error: { label: '异常', color: 'warning' },
  running: { label: '执行中', color: 'processing' }
}

export default function QualityTrendPage() {
  const { message } = App.useApp()
  const [results, setResults] = useState<QualityCheckResult[]>([])
  const [rules, setRules] = useState<QualityRule[]>([])
  const [summary, setSummary] = useState<QualitySummary>()
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<string>()
  const [ruleId, setRuleId] = useState<number>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [resultList, ruleList, summaryData] = await Promise.all([
        listAllResults(status, ruleId, 100),
        listRules(),
        getQualitySummary()
      ])
      setResults(resultList)
      setRules(ruleList)
      setSummary(summaryData)
    } catch {
      message.error('质量检查历史加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, status, ruleId])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const ruleName = useCallback(
    (id: number) => rules.find((rule) => rule.id === id)?.name ?? `规则 ${id}`,
    [rules]
  )

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

  const scoreText = summary && summary.score >= 0 ? `${summary.score}%` : '暂无数据'

  return (
    <div className="quality-trend-page">
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
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary">暂无检查记录。在数据质量页创建并运行规则，或配置定时调度后，历史会出现在这里。</Typography.Text> }}
        />
      </DataSurface>
    </div>
  )
}
