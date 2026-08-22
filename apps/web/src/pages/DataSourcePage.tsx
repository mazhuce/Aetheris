import { useCallback, useEffect, useState } from 'react'
import {
  App,
  Button,
  Card,
  Form,
  Input,
  InputNumber,
  Modal,
  Select,
  Space,
  Table,
  Tag
} from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import {
  createDataSource,
  listDataSources,
  type DataSource,
  type DataSourceInput
} from '@/api/metadata'

const statusMeta: Record<string, { color: string; text: string }> = {
  active: { color: 'success', text: '启用' },
  inactive: { color: 'default', text: '停用' }
}

const columns: TableColumnsType<DataSource> = [
  { title: '名称', dataIndex: 'name', key: 'name' },
  {
    title: '类型',
    dataIndex: 'type',
    key: 'type',
    render: (value: string) => <Tag>{value}</Tag>
  },
  { title: '主机', dataIndex: 'host', key: 'host' },
  {
    title: '端口',
    dataIndex: 'port',
    key: 'port',
    render: (value?: number) => value ?? '—'
  },
  {
    title: '数据库',
    dataIndex: 'databaseName',
    key: 'databaseName',
    render: (value?: string) => value || '—'
  },
  {
    title: '用户名',
    dataIndex: 'username',
    key: 'username',
    render: (value?: string) => value || '—'
  },
  {
    title: '状态',
    dataIndex: 'status',
    key: 'status',
    render: (value?: string) => {
      const meta = statusMeta[value ?? ''] ?? { color: 'default', text: value ?? '—' }
      return <Tag color={meta.color}>{meta.text}</Tag>
    }
  },
  {
    title: '创建时间',
    dataIndex: 'createdAt',
    key: 'createdAt',
    render: (value?: string) => (value ? new Date(value).toLocaleString('zh-CN') : '—')
  }
]

export default function DataSourcePage() {
  const { message } = App.useApp()
  const [data, setData] = useState<DataSource[]>([])
  const [loading, setLoading] = useState(false)
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<DataSourceInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setData(await listDataSources())
    } catch {
      setData([])
      message.error('数据源加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const handleOk = async () => {
    let values: DataSourceInput
    try {
      values = await form.validateFields()
    } catch {
      return
    }

    setSubmitting(true)
    try {
      await createDataSource(values)
      message.success('数据源已添加')
      setOpen(false)
      form.resetFields()
      await refresh()
    } catch {
      message.error('添加失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Card
      title="数据源管理"
      extra={
        <Space>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>
            刷新
          </Button>
          <Button type="primary" icon={<PlusOutlined />} onClick={() => setOpen(true)}>
            新增数据源
          </Button>
        </Space>
      }
    >
      <Table<DataSource>
        size="middle"
        rowKey="id"
        columns={columns}
        dataSource={data}
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无数据源，点击右上角「新增数据源」开始。' }}
      />

      <Modal
        title="新增数据源"
        open={open}
        onOk={() => void handleOk()}
        onCancel={() => setOpen(false)}
        confirmLoading={submitting}
        destroyOnClose
      >
        <Form form={form} layout="vertical" initialValues={{ type: 'postgresql', status: 'active' }}>
          <Form.Item
            name="name"
            label="名称"
            rules={[{ required: true, message: '请输入名称' }]}
          >
            <Input placeholder="例如：生产订单库" />
          </Form.Item>
          <Form.Item
            name="type"
            label="类型"
            rules={[{ required: true, message: '请选择类型' }]}
          >
            <Select
              options={[
                { value: 'postgresql', label: 'PostgreSQL' },
                { value: 'mysql', label: 'MySQL' }
              ]}
            />
          </Form.Item>
          <Form.Item
            name="host"
            label="主机"
            rules={[{ required: true, message: '请输入主机地址' }]}
          >
            <Input placeholder="例如：127.0.0.1" />
          </Form.Item>
          <Form.Item name="port" label="端口">
            <InputNumber min={1} max={65535} style={{ width: '100%' }} placeholder="例如：5432" />
          </Form.Item>
          <Form.Item name="databaseName" label="数据库名">
            <Input placeholder="例如：demo" />
          </Form.Item>
          <Form.Item name="username" label="用户名">
            <Input placeholder="可选" />
          </Form.Item>
          <Form.Item name="status" label="状态">
            <Select
              options={[
                { value: 'active', label: '启用' },
                { value: 'inactive', label: '停用' }
              ]}
            />
          </Form.Item>
        </Form>
      </Modal>
    </Card>
  )
}
