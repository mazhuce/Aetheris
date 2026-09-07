import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Row, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AlertOutlined, CheckCircleOutlined, DashboardOutlined, ExclamationCircleOutlined, ReloadOutlined, SafetyOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { INSTANCE_STATUS_META, listAllInstances, type TaskInstance } from '@/api/workflow'
import { getAlertStats, type AlertStats } from '@/api/system'
import { getQualitySummary, type QualitySummary } from '@/api/quality'
import { listNotifications } from '@/api/system'
import { formatDateTime } from '@/utils/format'

/** 概览页数据聚合自三个已就绪的接口：任务实例、告警统计、质量概览。
 *  任何一处失败不阻塞整页（分块降级）。 */
export default function OperationsOverviewPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [instances, setInstances] = useState<TaskInstance[]>([])
  const [alertStats, setAlertStats] = useState<AlertStats>()
  const [qualitySummary, setQualitySummary] = useState<QualitySummary>()
  const [recentAlerts, setRecentAlerts] = useState<{ id: number; title: string; level: string; createdAt?: string }[]>([])
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      // 各块独立 catch：单接口故障只影响对应卡片
      const [instanceResult, alertResult, qualityResult, alertListResult] = await Promise.allSettled([
        listAllInstances(undefined, 50),
        getAlertStats(),
        getQualitySummary(),
        listNotifications()
      ])
      if (instanceResult.status === 'fulfilled') setInstances(instanceResult.value)
      if (alertResult.status === 'fulfilled') setAlertStats(alertResult.value)
      if (qualityResult.status === 'fulfilled') setQualitySummary(qualityResult.value)
      if (alertListResult.status === 'fulfilled') {
        setRecentAlerts(alertListResult.value.slice(0, 8).map((item) => ({
          id: item.id, title: item.title, level: item.level, createdAt: item.createdAt
        })))
      }
    } catch {
      message.error('运行概览加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const successCount = instances.filter((instance) => instance.status === 'success').length
  const failedCount = instances.filter((instance) => instance.status === 'failed').length
  const runningCount = instances.filter((instance) => instance.status === 'running').length
  const successRate = instances.length
    ? Math.round((successCount / instances.length) * 100)
    : undefined

  const recentInstances = instances.slice(0, 10)

  const instanceColumns: TableColumnsType<TaskInstance> = [
    { title: '任务', dataIndex: 'taskId', key: 'taskId', width: 120, render: (value: number) => `任务 #${value}` },
    {
      title: '业务日期',
      dataIndex: 'bizDate',
      key: 'bizDate',
      width: 110,
      render: (value: string) => value || '—'
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: TaskInstance['status']) => {
        const meta = INSTANCE_STATUS_META[value]
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '触发',
      dataIndex: 'triggerType',
      key: 'triggerType',
      width: 90,
      render: (value?: string) => {
        if (!value) return '—'
        return value === 'manual' ? '手动' : value === 'schedule' ? '调度' : '重试'
      }
    },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 160,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 90,
      render: (value?: number) => (value === undefined || value === null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(1)} s` : `${value} ms`)
    }
  ]

  const levelColor: Record<string, string> = {
    critical: 'red',
    warning: 'orange',
    info: 'blue'
  }
  const levelLabel: Record<string, string> = {
    critical: '严重',
    warning: '警告',
    info: '提示'
  }

  const scoreText = qualitySummary && qualitySummary.score >= 0 ? `${qualitySummary.score}%` : '暂无'

  return (
    <div className="operations-overview-page">
      <PageHeader
        description="数据平台运行总览：任务执行、告警事件与数据质量的当前态势一屏尽览。"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <StatCard title="任务成功率" value={successRate === undefined ? '—' : `${successRate}%`} icon={<CheckCircleOutlined />} footer={`近 ${instances.length} 次执行`} tone="green" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="执行中任务" value={String(runningCount)} icon={<DashboardOutlined />} footer="当前正在运行" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="待处理告警" value={String((alertStats?.critical ?? 0) + (alertStats?.warning ?? 0))} icon={<AlertOutlined />} footer={`严重 ${alertStats?.critical ?? 0} · 警告 ${alertStats?.warning ?? 0}`} tone="orange" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="数据质量分" value={scoreText} icon={<SafetyOutlined />} footer={`未通过 ${qualitySummary?.failed ?? 0} 条规则`} tone="green" />
        </Col>
      </Row>

      <Row gutter={[16, 16]} className="operations-second-row">
        <Col xs={24} lg={14}>
          <DataSurface
            title="最近任务执行"
            description="最近 10 次实例"
            label="任务执行明细"
            extra={<Button size="small" type="link" onClick={() => navigate('/operations/tasks')}>查看全部</Button>}
          >
            <Table<TaskInstance>
              rowKey="id"
              size="small"
              columns={instanceColumns}
              dataSource={recentInstances}
              loading={loading}
              pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
              scroll={{ x: 700 }}
              locale={{ emptyText: '暂无任务执行记录' }}
            />
          </DataSurface>
        </Col>

        <Col xs={24} lg={10}>
          <DataSurface
            title="最新告警"
            description="与个人待办同源"
            label="告警列表"
            extra={<Button size="small" type="link" onClick={() => navigate('/operations/alerts')}>告警中心</Button>}
          >
            <Table
              rowKey="id"
              size="small"
              dataSource={recentAlerts}
              loading={loading}
              pagination={{ pageSize: 5, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
              locale={{ emptyText: '暂无告警' }}
              columns={[
                {
                  title: '级别',
                  dataIndex: 'level',
                  key: 'level',
                  width: 70,
                  render: (value: string) => (
                    <Tag color={levelColor[value] ?? 'default'}>{levelLabel[value] ?? value}</Tag>
                  )
                },
                {
                  title: '内容',
                  dataIndex: 'title',
                  key: 'title',
                  ellipsis: true
                },
                {
                  title: '时间',
                  dataIndex: 'createdAt',
                  key: 'createdAt',
                  width: 150,
                  render: (value?: string) => formatDateTime(value)
                }
              ]}
            />
            {failedCount > 0 && (
              <div style={{ marginTop: 12 }}>
                <Typography.Text type="warning" style={{ fontSize: 12 }}>
                  <ExclamationCircleOutlined /> 有 {failedCount} 次任务执行失败，建议前往任务监控排查。
                </Typography.Text>
              </div>
            )}
          </DataSurface>
        </Col>
      </Row>
    </div>
  )
}
