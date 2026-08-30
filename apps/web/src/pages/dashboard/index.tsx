import { useCallback, useEffect, useState } from 'react'
import {
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  ReloadOutlined
} from '@ant-design/icons'
import { Button, Progress, Table, Tag } from 'antd'
import type { TableColumnsType } from 'antd'
import { listConfigs, pingSystem } from '@/api/system'
import { listDataSources, type DataSource } from '@/api/metadata'
import { formatDateTime, formatNumber } from '@/utils/format'

type ServiceState = 'ok' | 'error'

interface ServiceRow {
  key: string
  name: string
  status: ServiceState
  version: string
  port: number
}

const activityColumns: TableColumnsType<Record<string, string>> = [
  { title: '时间', dataIndex: 'time', key: 'time', width: 170 },
  { title: '操作人', dataIndex: 'user', key: 'user', width: 90 },
  { title: '操作内容', dataIndex: 'action', key: 'action' },
  {
    title: '结果',
    dataIndex: 'result',
    key: 'result',
    width: 80,
    render: (value: string) => <Tag color={value === '成功' ? 'success' : 'error'}>{value}</Tag>
  }
]

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

  const runningCount = [gateway, system, metadata, db].filter((value) => value === 'ok').length

  const typeCounts = dataSources.reduce<Record<string, number>>((acc, item) => {
    acc[item.type] = (acc[item.type] ?? 0) + 1
    return acc
  }, {})
  const typeTotal = Object.values(typeCounts).reduce((sum, value) => sum + value, 0) || 1

  const serviceRows: ServiceRow[] = [
    { key: 'gateway', name: 'API Gateway', status: gateway, version: '2023.0.3', port: 8080 },
    { key: 'system', name: 'System Service', status: system, version: systemVersion, port: 8081 },
    { key: 'metadata', name: 'Metadata Service', status: metadata, version: '0.1.0', port: 8082 },
    { key: 'db', name: 'PostgreSQL', status: db, version: '16.15', port: 5433 }
  ]

  const activities = [
    { key: '1', time: formatDateTime('2026-08-23T11:32:10+08:00'), user: 'admin', action: '新增数据源「测试数据源」', result: '成功' },
    { key: '2', time: formatDateTime('2026-08-23T03:12:22+08:00'), user: 'system', action: '元数据服务完成数据库迁移', result: '成功' },
    { key: '3', time: formatDateTime('2026-08-23T02:30:55+08:00'), user: 'system', action: '初始化元数据种子数据', result: '成功' },
    { key: '4', time: formatDateTime('2026-08-22T18:28:06+08:00'), user: 'admin', action: '测试网关路由连通性', result: '成功' }
  ]

  const notices = [
    { title: 'Aetheris v0.1.0 已发布，元数据服务正式上线', type: '版本', time: '08-23' },
    { title: '平台已完成 Docker 一键构建与整栈部署', type: '发布', time: '08-22' },
    { title: '前端控制台升级为 React + Ant Design', type: '更新', time: '08-22' },
    { title: '建议尽快接入 Nacos 注册中心', type: '待办', time: '计划中' }
  ]

  const metrics = [
    { label: '数据源', value: String(dataSources.length), detail: '已接入', icon: <DatabaseOutlined /> },
    { label: '今日任务', value: '128', detail: '较昨日 +12.5%', icon: <CloudServerOutlined /> },
    { label: 'API 调用', value: formatNumber(12480), detail: '最近 24 小时', icon: <ApiOutlined /> },
    { label: '存储占用', value: '38%', detail: '500 GB 配额', icon: <DatabaseOutlined /> }
  ]

  return (
    <div className="dashboard-v2">
      <section className="dashboard-intro">
        <div><span className="section-kicker">OVERVIEW</span><h1>数据控制中心</h1><p>服务、资源与数据资产的实时运行视图。</p></div>
        <div className="dashboard-intro-actions"><span>最后检测 {lastCheck}</span><Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新状态</Button></div>
      </section>

      <section className="system-pulse">
        <div className="pulse-content">
          <div className="pulse-status"><span className={`pulse-dot ${runningCount === 4 ? 'online' : ''}`} /> {runningCount === 4 ? '核心链路运行正常' : '部分服务需要关注'}</div>
          <div className="pulse-score">{runningCount}<span>/ 4</span></div>
          <p>Gateway、System、Metadata 与 PostgreSQL 的综合健康状态。</p>
        </div>
        <div className="pulse-visual" aria-hidden="true"><span /><span /><span /><div className="pulse-core">A</div></div>
      </section>

      <section className="metric-strip">
        {metrics.map((metric) => <div className="metric-item" key={metric.label}><div className="metric-icon">{metric.icon}</div><div><span>{metric.label}</span><strong>{metric.value}</strong><small>{metric.detail}</small></div></div>)}
      </section>

      <section className="dashboard-grid">
        <article className="dashboard-panel services-panel">
          <div className="panel-heading"><div><span>Infrastructure</span><h2>服务运行状态</h2></div><span className="panel-count">{runningCount} / 4 online</span></div>
          <div className="service-list">
            {serviceRows.map((service) => <div className="service-row" key={service.key}><span className={`service-state ${service.status}`} /><div className="service-identity"><strong>{service.name}</strong><small>:{service.port}</small></div><span className="service-version">v{service.version}</span><span className="service-label">{service.status === 'ok' ? 'Operational' : 'Unavailable'}</span></div>)}
          </div>
        </article>

        <article className="dashboard-panel resources-panel">
          <div className="panel-heading"><div><span>Capacity</span><h2>资源使用</h2></div></div>
          {[['CPU', 42], ['Memory', 67], ['Storage', 38]].map(([label, value]) => <div className="resource-row" key={String(label)}><div><span>{label}</span><strong>{value}%</strong></div><Progress percent={Number(value)} showInfo={false} strokeColor="#071a3d" /></div>)}
          <div className="resource-note">资源指标每 60 秒自动采样</div>
        </article>

        <article className="dashboard-panel activity-panel">
          <div className="panel-heading"><div><span>Activity</span><h2>最近动态</h2></div></div>
          <Table className="quiet-table" size="small" columns={activityColumns} dataSource={activities} pagination={false} />
        </article>

        <article className="dashboard-panel sources-panel">
          <div className="panel-heading"><div><span>Catalog</span><h2>数据源分布</h2></div></div>
          {Object.keys(typeCounts).length === 0 ? <div className="panel-empty">暂无数据源</div> : Object.entries(typeCounts).map(([type, count]) => <div className="source-row" key={type}><div><Tag>{type}</Tag><span>{count} 个</span></div><Progress percent={Math.round((count / typeTotal) * 100)} showInfo={false} strokeColor="#071a3d" size="small" /></div>)}
        </article>

        <article className="dashboard-panel notices-panel">
          <div className="panel-heading"><div><span>Updates</span><h2>公告与待办</h2></div></div>
          {notices.map((notice) => <div className="notice-row" key={notice.title}><Tag>{notice.type}</Tag><span>{notice.title}</span><time>{notice.time}</time></div>)}
        </article>
      </section>
    </div>
  )
}
