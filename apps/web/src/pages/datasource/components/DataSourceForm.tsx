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
  Switch,
  Tag,
  Typography
} from 'antd'
import { ApiOutlined, CheckCircleOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { testDataSourceConfig, type DataSource, type DataSourceInput, type TestResult } from '@/api/metadata'
import { dataSourceGroups, getDataSourceCapabilities, getDataSourceDefinition, hiveConnectionPresets, isEmbeddedDataSource } from '../sourceCatalog'
import { credentialInput, readConnectionInput, type CredentialFormValues } from '../credentialInput'

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
  const [form] = Form.useForm<CredentialFormValues>()
  const writeEnabled = Form.useWatch('writeEnabled', form)
  const [testing, setTesting] = useState(false)
  const [testResult, setTestResult] = useState<TestResult | null>(null)
  const [clearLegacyOptions, setClearLegacyOptions] = useState(false)

  useEffect(() => {
    if (!open) return
    setTestResult(null)
    setClearLegacyOptions(false)
    if (editing) {
      form.resetFields()
      form.setFieldsValue({ ...editing, host: editing.host ?? undefined, databaseName: editing.databaseName ?? undefined,
        jdbcUrl: editing.jdbcUrl ?? undefined, connectionParams: editing.connectionParams ?? undefined,
        password: undefined, writeUsername: undefined, writePassword: undefined, writeEnabled: editing.writeConfigured === true })
    } else {
      form.resetFields()
      form.setFieldsValue({ type: 'postgresql', port: 5432, databaseName: 'postgres', status: 'active', writeEnabled: false })
    }
  }, [open, editing, form])

  const handleTypeChange = (type: string) => {
    const definition = getDataSourceDefinition(type)
    form.setFieldsValue({ port: definition.defaultPort, databaseName: definition.defaultDatabase, ...(isEmbeddedDataSource(type) ? { host: 'embedded' } : {}) })
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
      const result = await testDataSourceConfig(readConnectionInput(values))
      setTestResult(result)
      if (result.success) message.success('读取连接测试成功；未验证写入权限')
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
            测试读取连接
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

      {editing?.connectionConfigurationRedacted && <Alert
        className="form-section-space"
        type="warning"
        showIcon
        message="部分历史连接配置已隐藏"
        description={clearLegacyOptions
          ? '保存时将清除旧 URL 和连接参数。请确认主机和数据库地址正确；账户与密码仍通过独立字段填写。'
          : '历史配置含不支持的内容。请填写安全替换值，或清除旧 URL 和连接参数后使用主机、端口与数据库地址；未填写不代表旧值已删除。'}
        action={<Button disabled={clearLegacyOptions || testing} onClick={() => {
          form.setFieldsValue({ jdbcUrl: '', connectionParams: '' })
          setClearLegacyOptions(true)
          setTestResult(null)
        }}>清除旧 URL 和连接参数</Button>}
      />}

      <Form form={form} layout="vertical" onFinish={(values) => {
        const input = credentialInput(values, editing?.writeConfigured === true)
        onSubmit(clearLegacyOptions ? { ...input, jdbcUrl: input.jdbcUrl ?? '', connectionParams: input.connectionParams ?? '' } : input)
      }} onValuesChange={() => setTestResult(null)}>
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
                message={<Space wrap><Tag>{definition.label}</Tag><span>{definition.description}</span></Space>}
                description={<Space size={[4, 4]} wrap>{getDataSourceCapabilities(getFieldValue('type')).map((capability) => <Tag key={capability}>{capability}</Tag>)}</Space>}
              />
            )
          }}
        </Form.Item>

        <Row gutter={16}>
          <Col span={16}>
            <Form.Item name="host" label="主机地址" rules={[{ required: true, message: '请输入主机地址' }]}>
              <Input placeholder="数据库主机名或 IP；嵌入式类型填写 embedded" />
            </Form.Item>
          </Col>
          <Col span={8}>
            <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}>
              <InputNumber min={0} max={65535} className="full-width-control" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="databaseName" label="数据库 / Catalog" rules={[{ required: true, message: '请输入数据库名' }]}>
              <Input placeholder="Hive 通常填写 default" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="status" label="配置状态" extra="启用只表示配置状态；保存后请在列表测试连接以确认健康状态。">
              <Select options={[{ value: 'active', label: '启用' }, { value: 'inactive', label: '停用' }]} />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="username" label="查询 / 采集读取账户">
              <Input autoComplete="off" placeholder="无认证时可留空" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="password" label="读取密码" dependencies={['username']} rules={[{ validator: (_, value) => editing && form.getFieldValue('username') !== editing.username && !value?.trim() ? Promise.reject(new Error('更换读取账户必须填写新密码')) : Promise.resolve() }]} extra={editing ? '仅同一读取账户留空时保留原密码；更换账户或重新测试请填写新密码。' : undefined}>
              <Input.Password autoComplete="new-password" placeholder="无认证时可留空" />
            </Form.Item>
          </Col>
        </Row>

        <Form.Item name="writeEnabled" label="配置独立写入账户" valuePropName="checked" extra="可选。未配置时不能执行数仓加工或作为接入目标；查询、预览与采集始终使用读取账户。关闭并保存会明确禁用原写凭据。">
          <Switch />
        </Form.Item>
        {writeEnabled && <Row gutter={16}>
          <Col span={12}><Form.Item name="writeUsername" label="写入账户" preserve={false} dependencies={['username']} rules={[
            { required: !editing?.writeConfigured, message: '请输入独立写入账户' },
            { validator: (_, value) => value?.trim() && value.trim().toLowerCase() === form.getFieldValue('username')?.trim().toLowerCase() ? Promise.reject(new Error('写入账户必须与读取账户不同')) : Promise.resolve() }
          ]} extra={editing?.writeConfigured ? '已配置写账户；留空保留。填写账户名称时请同时提供新密码。' : undefined}><Input autoComplete="off" /></Form.Item></Col>
          <Col span={12}><Form.Item name="writePassword" label="写入密码" preserve={false} dependencies={['writeUsername']} rules={[
            { validator: (_, value) => (!editing?.writeConfigured || form.getFieldValue('writeUsername')?.trim()) && !value?.trim() ? Promise.reject(new Error('配置或更换写入账户必须填写新密码')) : Promise.resolve() }
          ]} extra="密码不回显；读取连接测试不证明写入权限。"><Input.Password autoComplete="new-password" /></Form.Item></Col>
        </Row>}

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
          message={testResult.success ? `读取连接成功 · ${testResult.latencyMs ?? '—'} ms（未验证写入）` : '读取连接失败'}
          description={testResult.success
            ? `${testResult.databaseProduct ?? '数据库'} ${testResult.databaseVersion ?? ''} · ${testResult.driverName ?? 'JDBC Driver'} ${testResult.driverVersion ?? ''}`
            : testResult.message}
        />
      )}
    </Modal>
  )
}
