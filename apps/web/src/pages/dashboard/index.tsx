import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons'
import { Button, Progress, Tag } from 'antd'
import { getServiceHealth, listActivity, type AuditLog, type ServiceStatus } from '@/api/system'
import { listDataSources, type DataSource } from '@/api/metadata'
import { formatNumber } from '@/utils/format'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { EmptyState, ErrorState, LoadingState } from '@/components/FeedbackState'

export default function Dashboard() {
  const [serviceRows, setServiceRows] = useState<ServiceStatus[]>([])
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [activities, setActivities] = useState<AuditLog[]>([])
  const [lastCheck, setLastCheck] = useState('—')
  const [healthChecked, setHealthChecked] = useState(false)
  const [healthError, setHealthError] = useState(false)
  const [sourcesError, setSourcesError] = useState(false)
  const [activityError, setActivityError] = useState(false)

  const refresh = useCallback(async () => {
    const [health, sources, activity] = await Promise.allSettled([
      getServiceHealth(), listDataSources(), listActivity(10)
    ])
    setServiceRows(health.status === 'fulfilled' ? health.value : [])
    setDataSources(sources.status === 'fulfilled' ? sources.value : [])
    setActivities(activity.status === 'fulfilled' ? activity.value : [])
    setHealthChecked(true)
    setHealthError(health.status === 'rejected')
    setSourcesError(sources.status === 'rejected')
    setActivityError(activity.status === 'rejected')

    setLastCheck(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const runningCount = serviceRows.filter((service) => service.status === 'UP').length
  const healthScore = serviceRows.length ? Math.round((runningCount / serviceRows.length) * 100) : 0
  const allHealthy = serviceRows.length > 0 && runningCount === serviceRows.length

  const typeCounts = useMemo(() => dataSources.reduce<Record<string, number>>((acc, item) => {
    acc[item.type] = (acc[item.type] ?? 0) + 1
    return acc
  }, {}), [dataSources])
  const typeTotal = Object.values(typeCounts).reduce((sum, value) => sum + value, 0) || 1

  const metrics = [
    { label: '数据源', value: sourcesError ? '—' : formatNumber(dataSources.length), detail: sourcesError ? '读取失败' : '已接入', icon: <DatabaseOutlined /> },
    { label: '在线服务', value: healthError || !healthChecked ? '—' : `${runningCount}/${serviceRows.length}`, detail: healthError ? '探活失败' : '实时探活', icon: <CloudServerOutlined /> },
    { label: '数据源类型', value: sourcesError ? '—' : formatNumber(Object.keys(typeCounts).length), detail: sourcesError ? '读取失败' : '当前目录', icon: <ApiOutlined /> },
    { label: '平台健康度', value: healthError || !healthChecked ? '—' : `${healthScore}%`, detail: healthError ? '无法判断' : '核心服务', icon: <SafetyCertificateOutlined /> }
  ]

  const attentionItems = serviceRows.filter((service) => service.status === 'DOWN')

  return (
    <div className="dashboard-v3">
      <PageHeader
        description="汇总展示平台连接、核心服务与数据目录的运行状态。"
        meta={(
          <StatusBadge tone={!healthChecked ? 'neutral' : healthError ? 'danger' : allHealthy ? 'success' : 'warning'}>
            {!healthChecked ? '正在检测服务' : healthError ? '健康中心暂不可用' : allHealthy ? '核心服务运行正常' : `${attentionItems.length} 项需要关注`}
          </StatusBadge>
        )}
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新状态</Button>}
      />

      <section className="metric-grid" aria-label="平台关键指标">
        {metrics.map((metric) => (
          <article className="metric-item" key={metric.label}>
            <div className="metric-icon">{metric.icon}</div>
            <div><span>{metric.label}</span><strong>{metric.value}</strong><small>{metric.detail}</small></div>
          </article>
        ))}
      </section>

      <section className="dashboard-grid">
        <DataSurface
          className="services-panel"
          title="服务运行状态"
          description={`最后检测 ${lastCheck}`}
          extra={<StatusBadge tone={healthError ? 'danger' : allHealthy ? 'success' : 'warning'}>{healthError ? '读取失败' : `${runningCount}/${serviceRows.length} 在线`}</StatusBadge>}
        >
          {!healthChecked ? <LoadingState rows={5} /> : healthError ? <ErrorState description="无法读取服务健康状态，请确认 system-service 与网关可用。" onRetry={() => void refresh()} /> : <div className="service-list">
            {serviceRows.map((service) => (
              <div className="service-row" key={service.name}>
                <span className={`service-state ${service.status === 'UP' ? 'ok' : 'error'}`} aria-hidden="true" />
                <div className="service-identity"><strong>{service.name}</strong></div>
                <span className="service-version" title={service.detail}>{service.detail || '健康探测'}</span>
                <StatusBadge tone={service.status === 'UP' ? 'success' : 'danger'} dot={false}>
                  {service.status === 'UP' ? '正常' : '不可用'}
                </StatusBadge>
              </div>
            ))}
          </div>}
        </DataSurface>

        <DataSurface className="activity-panel" title="最近动态" description="来自平台真实审计事件">
          {activityError ? <ErrorState description="无法读取审计活动，请稍后重试。" onRetry={() => void refresh()} /> : activities.length ? (
            <div className="activity-list">
              {activities.map((activity) => (
                <div className="notice-row" key={activity.id}>
                  <Tag color={activity.statusCode < 400 ? 'default' : 'error'}>{activity.method}</Tag>
                  <span title={activity.path}>{activity.username} · {activityLabel(activity.path)}</span>
                  <time>{activity.createdAt ? new Date(activity.createdAt).toLocaleString('zh-CN', { hour12: false }) : '—'}</time>
                </div>
              ))}
            </div>
          ) : (
            <EmptyState compact title="暂无操作动态" description="完成新增、编辑、执行或发布操作后会显示在这里。" />
          )}
        </DataSurface>

        <div className="dashboard-side-stack">
          <DataSurface title="需要关注" description="基于当前真实探活结果">
            {healthError ? (
              <ErrorState description="健康数据不可用，当前不能判断平台是否存在异常。" onRetry={() => void refresh()} />
            ) : attentionItems.length === 0 ? (
              <EmptyState compact title="暂无异常" description={healthChecked ? '核心服务当前均可访问。' : '正在等待健康探测结果。'} />
            ) : (
              <div className="notice-list">
                {attentionItems.map((service) => (
                  <div className="notice-row" key={service.name}>
                    <Tag color="error">异常</Tag>
                    <span>{service.name} 当前不可用</span>
                    <time>实时</time>
                  </div>
                ))}
              </div>
            )}
          </DataSurface>

          <DataSurface title="数据源分布" description={`${dataSources.length} 个已接入连接`}>
            {sourcesError ? <ErrorState description="无法读取数据源目录，请稍后重试。" onRetry={() => void refresh()} /> : Object.keys(typeCounts).length === 0 ? (
              <EmptyState compact title="暂无数据源" description="完成第一个数据源接入后，这里会展示类型分布。" />
            ) : (
              <div className="source-list">
                {Object.entries(typeCounts).map(([type, count]) => (
                  <div className="source-row" key={type}>
                    <div><Tag>{type}</Tag><span>{count} 个</span></div>
                    <Progress percent={Math.round((count / typeTotal) * 100)} showInfo={false} size="small" />
                  </div>
                ))}
              </div>
            )}
          </DataSurface>
        </div>
      </section>
    </div>
  )
}

function activityLabel(path: string) {
  const labels: Array<[string, string]> = [
    ['/api/metadata/metric-definitions', '维护指标定义'],
    ['/api/metadata/logical-models', '维护逻辑模型'],
    ['/api/metadata/semantic-dimensions', '维护语义维度'],
    ['/api/metadata/business-domains', '维护业务域'],
    ['/api/workflow/tasks', '操作开发任务'],
    ['/api/quality/rules', '操作质量规则'],
    ['/api/ingestion/tasks', '操作接入任务'],
    ['/api/analytics/dashboards', '维护数据看板'],
    ['/api/analytics/datasets', '维护分析数据集']
  ]
  return labels.find(([prefix]) => path.startsWith(prefix))?.[1] ?? path.replace('/api/', '')
}
