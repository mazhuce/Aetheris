import { useCallback, useEffect, useState } from 'react'
import {
  AlertOutlined,
  ApiOutlined,
  CheckCircleOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  HddOutlined
} from '@ant-design/icons'
import { Card, Col, Progress, Row, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import StatCard from '@/components/StatCard'
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

const serviceColumns: TableColumnsType<ServiceRow> = [
  { title: '服务', dataIndex: 'name', key: 'name' },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    render: (status: ServiceState) =>
      status === 'ok' ? <Tag color="success">运行中</Tag> : <Tag color="error">异常</Tag>
  },
  { title: '版本', dataIndex: 'version', key: 'version' },
  { title: '端口', dataIndex: 'port', key: 'port' }
]

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

  return (
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Row gutter={[16, 16]}>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="数据源总数" value={String(dataSources.length)} icon={<DatabaseOutlined />} footer="已接入的数据源" />
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="运行中服务" value={`${runningCount}/4`} icon={<CloudServerOutlined />} trend={runningCount === 4 ? 0 : -25} footer="核心服务健康度" />
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="今日任务" value="128" icon={<CheckCircleOutlined />} trend={12.5} footer="累计执行任务" />
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="API 调用" value={formatNumber(12480)} icon={<ApiOutlined />} trend={8.3} footer="近 24 小时" />
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="存储用量" value="38%" icon={<HddOutlined />} trend={-2.1} footer="共 500GB" />
        </Col>
        <Col xs={12} sm={8} xl={4}>
          <StatCard title="待处理告警" value="3" icon={<AlertOutlined />} trend={1} footer="其中 1 条为高优先级" />
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={16}>
          <Card
            size="small"
            title="服务健康"
            extra={<Typography.Text type="secondary" style={{ fontSize: 12 }}>最后检测 {lastCheck}</Typography.Text>}
          >
            <Table size="small" columns={serviceColumns} dataSource={serviceRows} pagination={false} />
          </Card>
        </Col>
        <Col xs={24} xl={8}>
          <Card size="small" title="资源使用">
            <Space direction="vertical" size={16} style={{ width: '100%' }}>
              <div>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text>CPU 使用率</Typography.Text>
                  <Typography.Text type="secondary">42%</Typography.Text>
                </Space>
                <Progress percent={42} showInfo={false} strokeColor="#1677ff" size="small" />
              </div>
              <div>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text>内存使用率</Typography.Text>
                  <Typography.Text type="secondary">67%</Typography.Text>
                </Space>
                <Progress percent={67} showInfo={false} strokeColor="#52c41a" size="small" />
              </div>
              <div>
                <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                  <Typography.Text>磁盘使用率</Typography.Text>
                  <Typography.Text type="secondary">38%</Typography.Text>
                </Space>
                <Progress percent={38} showInfo={false} strokeColor="#faad14" size="small" />
              </div>
            </Space>
          </Card>
        </Col>
      </Row>

      <Row gutter={[16, 16]}>
        <Col xs={24} xl={12}>
          <Card size="small" title="数据源类型分布">
            {Object.keys(typeCounts).length === 0 ? (
              <Typography.Text type="secondary">暂无数据源</Typography.Text>
            ) : (
              <Space direction="vertical" size={12} style={{ width: '100%' }}>
                {Object.entries(typeCounts).map(([type, count]) => (
                  <div key={type}>
                    <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                      <Tag>{type}</Tag>
                      <Typography.Text type="secondary">{count} 个</Typography.Text>
                    </Space>
                    <Progress percent={Math.round((count / typeTotal) * 100)} showInfo={false} strokeColor="#1677ff" size="small" />
                  </div>
                ))}
              </Space>
            )}
          </Card>
        </Col>
        <Col xs={24} xl={12}>
          <Card size="small" title="最近动态">
            <Table size="small" columns={activityColumns} dataSource={activities} pagination={false} />
          </Card>
        </Col>
      </Row>

      <Card size="small" title="公告与待办">
        {notices.map((notice) => (
          <div
            key={notice.title}
            style={{ display: 'flex', justifyContent: 'space-between', padding: '10px 0', borderBottom: '1px solid #f0f0f0' }}
          >
            <Space>
              <Tag color={notice.type === '版本' ? 'blue' : notice.type === '待办' ? 'orange' : 'default'}>{notice.type}</Tag>
              <Typography.Text>{notice.title}</Typography.Text>
            </Space>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>{notice.time}</Typography.Text>
          </div>
        ))}
      </Card>
    </Space>
  )
}
