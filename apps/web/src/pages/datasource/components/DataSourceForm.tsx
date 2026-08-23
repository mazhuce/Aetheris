import { useEffect } from 'react'
import { Col, Form, Input, InputNumber, Modal, Row, Select } from 'antd'
import type { DataSource, DataSourceInput } from '@/api/metadata'

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
  const [form] = Form.useForm<DataSourceInput>()

  useEffect(() => {
    if (open) {
      if (editing) form.setFieldsValue(editing)
      else form.resetFields()
    }
  }, [open, editing, form])

  return (
    <Modal
      title={editing ? '编辑数据源' : '新增数据源'}
      open={open}
      onCancel={onCancel}
      onOk={() => form.submit()}
      confirmLoading={submitting}
      width={640}
    >
      <Form
        form={form}
        layout="vertical"
        initialValues={{ type: 'postgresql', status: 'active' }}
        onFinish={onSubmit}
      >
        <Row gutter={16}>
          <Col span={12}>
            <Form.Item name="name" label="数据源名称" rules={[{ required: true, message: '请输入名称' }]}>
              <Input placeholder="例如：生产订单库" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="type" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
              <Select
                options={[
                  { value: 'postgresql', label: 'PostgreSQL' },
                  { value: 'mysql', label: 'MySQL' },
                  { value: 'hive2', label: 'Spark Thrift / Hive2' }
                ]}
              />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="host" label="主机地址" rules={[{ required: true, message: '请输入主机地址' }]}>
              <Input placeholder="例如：127.0.0.1" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="port" label="端口">
              <InputNumber min={1} max={65535} style={{ width: '100%' }} placeholder="例如：5432" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="databaseName" label="数据库名">
              <Input placeholder="例如：demo" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="username" label="用户名">
              <Input placeholder="可选" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="password" label="密码">
              <Input.Password placeholder="可选" />
            </Form.Item>
          </Col>
          <Col span={12}>
            <Form.Item name="status" label="状态">
              <Select
                options={[
                  { value: 'active', label: '启用' },
                  { value: 'inactive', label: '停用' }
                ]}
              />
            </Form.Item>
          </Col>
        </Row>
      </Form>
    </Modal>
  )
}
