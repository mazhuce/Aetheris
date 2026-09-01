import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons'
import { Button, Progress, Tag } from 'antd'
import { listConfigs, pingSystem } from '@/api/system'
import { listDataSources, type DataSource } from '@/api/metadata'
import { formatNumber } from '@/utils/format'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { EmptyState } from '@/components/FeedbackState'

type ServiceState = 'ok' | 'error'

interface ServiceRow {
  key: string
  name: string
  status: ServiceState
  version?: string
  port: number
}

export default function Dashboard() {
  const [gateway, setGateway] = useState<ServiceState>('error')
  const [system, setSystem] = useState<ServiceState>('error')
  const [metadata, setMetadata] = useState<ServiceState>('error')
  const [db, setDb] = useState<ServiceState>('error')
  const [systemVersion, setSystemVersion] = useState('—')
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [lastCheck, setLastCheck] = useState('—')

  const refresh = useCallback(async () => {
    try {
      const ping = await pingSystem()
      setGateway('ok')
      setSystem('ok')
      setSystemVersion(ping.version)
    } catch {
      setGateway('error')
      setSystem('error')
    }

    try {
      await listConfigs()
      setDb('ok')
    } catch {
      setDb('error')
    }

    try {
      setDataSources(await listDataSources())
      setMetadata('ok')
    } catch {
      setMetadata('error')
      setDataSources([])
    }

    setLastCheck(new Date().toLocaleTimeString('zh-CN', { hour12: false }))
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const serviceRows: ServiceRow[] = useMemo(() => [
    { key: 'gateway', name: 'API Gateway', status: gateway, port: 8080 },
    { key: 'system', name: 'System Service', status: system, version: systemVersion === '—' ? undefined : systemVersion, port: 8081 },
    { key: 'metadata', name: 'Metadata Service', status: metadata, port: 8082 },
    { key: 'db', name: 'PostgreSQL', status: db, port: 5433 }
  ], [db, gateway, metadata, system, systemVersion])

  const runningCount = serviceRows.filter((service) => service.status === 'ok').length
  const healthScore = Math.round((runningCount / serviceRows.length) * 100)

  const typeCounts = useMemo(() => dataSources.reduce<Record<string, number>>((acc, item) => {
    acc[item.type] = (acc[item.type] ?? 0) + 1
    return acc
  }, {}), [dataSources])
  const typeTotal = Object.values(typeCounts).reduce((sum, value) => sum + value, 0) || 1

  const metrics = [
    { label: '数据源', value: formatNumber(dataSources.length), detail: '已接入', icon: <DatabaseOutlined /> },
    { label: '在线服务', value: `${runningCount}/${serviceRows.length}`, detail: '实时探活', icon: <CloudServerOutlined /> },
    { label: '数据源类型', value: formatNumber(Object.keys(typeCounts).length), detail: '当前目录', icon: <ApiOutlined /> },
    { label: '平台健康度', value: `${healthScore}%`, detail: '核心服务', icon: <SafetyCertificateOutlined /> }
  ]

  const attentionItems = serviceRows.filter((service) => service.status === 'error')

  return (
    <div className="dashboard-v3">
      <PageHeader
        description="汇总展示平台连接、核心服务与数据目录的运行状态。"
        meta={(
          <StatusBadge tone={runningCount === serviceRows.length ? 'success' : 'warning'}>
            {runningCount === serviceRows.length ? '核心服务运行正常' : `${attentionItems.length} 项需要关注`}
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
          extra={<StatusBadge tone={runningCount === serviceRows.length ? 'success' : 'warning'}>{runningCount}/{serviceRows.length} 在线</StatusBadge>}
        >
          <div className="service-list">
            {serviceRows.map((service) => (
              <div className="service-row" key={service.key}>
                <span className={`service-state ${service.status}`} aria-hidden="true" />
                <div className="service-identity"><strong>{service.name}</strong><small>:{service.port}</small></div>
                <span className="service-version">{service.version ? `v${service.version}` : 'version n/a'}</span>
                <StatusBadge tone={service.status === 'ok' ? 'success' : 'danger'} dot={false}>
                  {service.status === 'ok' ? '正常' : '不可用'}
                </StatusBadge>
              </div>
            ))}
          </div>
        </DataSurface>

        <DataSurface className="activity-panel" title="最近动态" description="操作活动流">
          <EmptyState compact title="尚未接入活动流" description="接入审计或任务事件后，最近操作会显示在这里。" />
        </DataSurface>

        <div className="dashboard-side-stack">
          <DataSurface title="需要关注" description="基于当前真实探活结果">
            {attentionItems.length === 0 ? (
              <EmptyState compact title="暂无异常" description="核心服务当前均可访问。" />
            ) : (
              <div className="notice-list">
                {attentionItems.map((service) => (
                  <div className="notice-row" key={service.key}>
                    <Tag color="error">异常</Tag>
                    <span>{service.name} 当前不可用</span>
                    <time>实时</time>
                  </div>
                ))}
              </div>
            )}
          </DataSurface>

          <DataSurface title="数据源分布" description={`${dataSources.length} 个已接入连接`}>
            {Object.keys(typeCounts).length === 0 ? (
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
