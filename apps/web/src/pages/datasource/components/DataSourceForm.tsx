import { useEffect, useState } from 'react'
import {
  Alert,
  App,
  Button,
  Collapse,
  Col,
  Form,
  Input,
  InputNumber,
  Modal,
  Row,
  Select,
  Space,
  Tag,
  Typography
} from 'antd'
import { ApiOutlined, CheckCircleOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { testDataSourceConfig, type DataSource, type DataSourceInput, type TestResult } from '@/api/metadata'
import { dataSourceGroups, getDataSourceDefinition, hiveConnectionPresets } from '../sourceCatalog'

interface DataSourceFormProps {
  open: boolean
  editing?: DataSource | null
  submitting: boolean
  onCancel: () => void
  onSubmit: (values: DataSourceInput) => void
}

export default function DataSourceForm({
  open,
  editing,
  submitting,
  onCancel,
  onSubmit
}: DataSourceFormProps) {
  const { message } = App.useApp()
  const [form] = Form.useForm<DataSourceInput>()
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TestResult | null>(null)

  useEffect(() => {
    if (!open) return
    setTestResult(null)
    if (editing) {
      form.setFieldsValue({ ...editing, password: undefined })
    } else {
      form.resetFields()
      form.setFieldsValue({ type: 'postgresql', port: 5432, databaseName: 'postgres', status: 'active' })
    }
  }, [open, editing, form])

  const handleTypeChange = (type: string) => {
    const definition = getDataSourceDefinition(type)
    form.setFieldsValue({ port: definition.defaultPort, databaseName: definition.defaultDatabase })
    setTestResult(null)
  }

  const applyPreset = (presetKey: string) => {
    const preset = hiveConnectionPresets.find((item) => item.key === presetKey)
    if (!preset) return
    form.setFieldsValue({ ...preset.values, status: 'active' })
    setTestResult(null)
    message.success(`已填入「${preset.title}」配置，请先测试连接`)
  }

  const handleTest = async () => {
    try {
      const values = await form.validateFields()
      setTesting(true)
      setTestResult(null)
      const result = await testDataSourceConfig(values)
      setTestResult(result)
      if (result.success) message.success('真实连接测试成功')
      else message.error(result.message)
    } catch (error) {
      if (error instanceof Error) message.error(error.message)
    } finally {
      setTesting(false)
    }
  }

  return (
    <Modal
      title={editing ? '编辑数据源' : '接入新数据源'}
      open={open}
      onCancel={onCancel}
      width={780}
      destroyOnHidden
      footer={
        <Space>
          <Button onClick={onCancel}>取消</Button>
          <Button icon={<ThunderboltOutlined />} loading={testing} onClick={() => void handleTest()}>
            测试连接
          </Button>
          <Button type="primary" loading={submitting} onClick={() => form.submit()}>
            {editing ? '保存修改' : '确认接入'}
          </Button>
        </Space>
      }
    >
      {!editing && (
        <div className="datasource-preset-panel">
          <div>
            <Typography.Text strong>快速接入 Hive 测试环境</Typography.Text>
            <Typography.Paragraph type="secondary" className="helper-paragraph">
              预设只负责填写地址，点击“测试连接”后才会向真实服务发起 JDBC 请求。
            </Typography.Paragraph>
          </div>
          <Space wrap>
            {hiveConnectionPresets.map((preset) => (
              <Button key={preset.key} icon={<ApiOutlined />} onClick={() => applyPreset(preset.key)}>
                {preset.title}
              </Button>
            ))}
          </Space>
        </div>
      )}

      <Form form={form} layout="vertical" onFinish={onSubmit} onValuesChange={() => setTestResult(null)}>
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label="数据源名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="例如：生产订单库" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="type" label="数据源类型" rules={[{ required: true, message: '请选择类型' }]}>
              <Select showSearch optionFilterProp="label" options={dataSourceGroups} onChange={handleTypeChange} />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item noStyle shouldUpdate={(previous, current) => previous.type !== current.type}>
          {({ getFieldValue }) => {
            const definition = getDataSourceDefinition(getFieldValue('type'))
            return (
              <Alert
                className="datasource-type-hint"
                type="info"
                showIcon
                message={<Space><Tag>{definition.label}</Tag><span>{definition.description}</span></Space>}
              />
            )
          }}
        </Form.Item>

        <Row gutter={16}>
          <Col span={16}>
            <Form.Item name="host" label="主机地址" rules={[{ required: true, message: '请输入主机地址' }]}>
              <Input placeholder="数据库主机名或 IP，例如 host.docker.internal" />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
              <InputNumber min={1} max={65535} className="full-width-control" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="databaseName" label="数据库 / Catalog" rules={[{ required: true, message: '请输入数据库名' }]}>
              <Input placeholder="Hive 通常填写 default" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="status" label="接入状态">
              <Select options={[{ value: 'active', label: '启用' }, { value: 'inactive', label: '停用' }]} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="username" label="用户名">
              <Input autoComplete="off" placeholder="无认证时可留空" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="password" label="密码" extra={editing ? '留空则保存时沿用原密码；重新测试时请填写密码。' : undefined}>
              <Input.Password autoComplete="new-password" placeholder="无认证时可留空" />
            </Form.Item>
          </Col>
        </Row>

        <Collapse
          ghost
          className="datasource-advanced"
          items={[{
            key: 'advanced',
            label: '高级连接设置',
            children: (
              <>
                <Form.Item name="jdbcUrl" label="自定义 JDBC URL" extra="留空时由系统根据类型、主机、端口和数据库自动生成。">
                  <Input placeholder="例如 jdbc:hive2://host:10000/default" />
                </Form.Item>
                <Form.Item name="connectionParams" label="连接参数" extra="Hive 使用分号参数，如 auth=noSasl；其他数据库使用 key=value&key2=value2。">
                  <Input placeholder="例如 auth=noSasl" />
                </Form.Item>
                <Form.Item name="description" label="用途说明">
                  <Input.TextArea rows={2} maxLength={512} showCount placeholder="记录环境、负责人或网络访问说明" />
                </Form.Item>
              </>
            )
          }]}
        />
      </Form>

      {testResult && (
        <Alert
          className="form-section-space"
          type={testResult.success ? 'success' : 'error'}
          showIcon
          icon={testResult.success ? <CheckCircleOutlined /> : undefined}
          message={testResult.success ? `连接成功 · ${testResult.latencyMs ?? '—'} ms` : '连接失败'}
          description={testResult.success
            ? `${testResult.databaseProduct ?? '数据库'} ${testResult.databaseVersion ?? ''} · ${testResult.driverName ?? 'JDBC Driver'} ${testResult.driverVersion ?? ''}`
            : testResult.message}
        />
      )}
    </Modal>
  )
}
