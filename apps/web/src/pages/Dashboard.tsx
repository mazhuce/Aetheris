import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Card,
  Col,
  Row,
  Space,
  Table,
  Tag,
  Typography
} from 'antd'
import {
  ApiOutlined,
  CloudServerOutlined,
  DatabaseOutlined,
  ReloadOutlined,
  SafetyCertificateOutlined
} from '@ant-design/icons'
import MetricCard, { type Status } from '../components/MetricCard'
import { listConfigs, pingSystem, type PingResult, type SystemConfig } from '../api/system'

interface HealthRow {
  key: string
  service: string
  status: Status
  detail: string
}

const healthColumns = [
  {
    title: '服务',
    dataIndex: 'service',
    key: 'service'
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    render: (status: Status) => {
      const color = status === 'ok' ? 'success' : status === 'error' ? 'error' : 'warning'
      const text = status === 'ok' ? '在线' : status === 'error' ? '离线' : '检测中'
      return <Tag color={color}>{text}</Tag>
    }
  },
  {
    title: '说明',
    dataIndex: 'detail',
    key: 'detail'
  }
]

const configColumns = [
  {
    title: 'Key',
    dataIndex: 'configKey',
    key: 'configKey',
    render: (value: string) => <Typography.Text code>{value}</Typography.Text>
  },
  {
    title: 'Value',
    dataIndex: 'configValue',
    key: 'configValue'
  },
  {
    title: '说明',
    dataIndex: 'description',
    key: 'description',
    render: (value?: string) => value || '—'
  }
]

export default function Dashboard() {
  const [gateway, setGateway] = useState<Status>('pending')
  const [system, setSystem] = useState<Status>('pending')
  const [db, setDb] = useState<Status>('pending')
  const [ping, setPing] = useState<PingResult | null>(null)
  const [configs, setConfigs] = useState<SystemConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [lastCheck, setLastCheck] = useState('—')

  const refresh = useCallback(async () => {
    setLoading(true)
    setGateway('pending')
    setSystem('pending')
    setDb('pending')

    try {
      setPing(await pingSystem())
      setGateway('ok')
      setSystem('ok')
    } catch {
      setPing(null)
      setGateway('error')
      setSystem('error')
      setLastCheck(new Date().toLocaleTimeString('zh-CN'))
      setLoading(false)
      return
    }

    try {
      setConfigs(await listConfigs())
      setDb('ok')
    } catch {
      setConfigs([])
      setDb('error')
    }

    setLastCheck(new Date().toLocaleTimeString('zh-CN'))
    setLoading(false)
  }, [])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const healthData: HealthRow[] = [
    {
      key: 'gateway',
      service: 'API Gateway',
      status: gateway,
      detail: '统一入口 · 8080'
    },
    {
      key: 'system',
      service: 'System Service',
      status: system,
      detail: ping ? `${ping.service} · v${ping.version}` : '等待响应'
    },
    {
      key: 'db',
      service: 'PostgreSQL',
      status: db,
      detail: '数据持久层 · 5432'
    }
  ]

  return (
    <Space direction="vertical" size={16} style={{ display: 'flex' }}>
      <Row gutter={16}>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            icon={<ApiOutlined />}
            label="API Gateway"
            value=""
            status={gateway}
            footer="统一入口 · 8080"
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            icon={<CloudServerOutlined />}
            label="System Service"
            value={ping ? `v${ping.version}` : '—'}
            status={system}
            footer={ping?.service || '等待响应'}
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            icon={<DatabaseOutlined />}
            label="PostgreSQL"
            value=""
            status={db}
            footer="数据持久层 · 5432"
          />
        </Col>
        <Col xs={24} sm={12} xl={6}>
          <MetricCard
            icon={<SafetyCertificateOutlined />}
            label="平台版本"
            value="0.1.0"
            footer="Aetheris 数据中台"
          />
        </Col>
      </Row>

      <Card
        title="服务健康"
        extra={
          <Space size={12}>
            <Typography.Text type="secondary" style={{ fontSize: 12 }}>
              最后检测 {lastCheck}
            </Typography.Text>
            <Button
              size="small"
              icon={<ReloadOutlined />}
              onClick={() => void refresh()}
              loading={loading}
            >
              刷新
            </Button>
          </Space>
        }
      >
        <Table
          size="middle"
          columns={healthColumns}
          dataSource={healthData}
          pagination={false}
        />
      </Card>

      <Card
        title="系统配置"
        extra={
          <Typography.Text type="secondary" style={{ fontSize: 12 }}>
            来源 system_config
          </Typography.Text>
        }
      >
        <Table
          size="middle"
          columns={configColumns}
          dataSource={configs}
          rowKey="id"
          pagination={false}
          locale={{ emptyText: '数据库暂不可用，启动服务后自动显示。' }}
        />
      </Card>
    </Space>
  )
}
