import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Form, Input, Modal, Popconfirm, Space, Table, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  createConfig,
  deleteConfig,
  listConfigs,
  updateConfig,
  type SystemConfig,
  type SystemConfigInput
} from '@/api/system'
import { formatDateTime } from '@/utils/format'

export default function SystemConfigPage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<SystemConfig[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<SystemConfig | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<SystemConfigInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listConfigs())
    } catch {
      message.error('系统配置加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (open) {
      if (editing) form.setFieldsValue(editing)
      else form.resetFields()
    }
  }, [open, editing, form])

  const filtered = useMemo(() => {
    return allData.filter((item) => !keyword || item.configKey.includes(keyword) || item.configValue.includes(keyword))
  }, [allData, keyword])

  const handleSubmit = async (values: SystemConfigInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateConfig(editing.id, values)
        message.success('配置已更新')
      } else {
        await createConfig(values)
        message.success('配置已添加')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteConfig(id)
      message.success('配置已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const columns: TableColumnsType<SystemConfig> = [
    { title: 'Key', dataIndex: 'configKey', key: 'configKey', width: 200, render: (value: string) => <Typography.Text code>{value}</Typography.Text> },
    { title: 'Value', dataIndex: 'configValue', key: 'configValue' },
    { title: '说明', dataIndex: 'description', key: 'description', width: 240, render: (value?: string) => value || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 120,
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => { setEditing(record); setOpen(true) }}>编辑</Button>
          <Popconfirm title="确定删除该配置？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>删除</Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="系统配置"
        description="维护平台运行所需的全局配置项。"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增配置
          </Button>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>Key/Value</span>
            <Input placeholder="搜索 Key 或 Value" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear style={{ width: 200 }} />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </Card>

      <Card size="small">
        <Table<SystemConfig>
          size="small"
          bordered
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无配置' }}
        />
      </Card>

      <Modal
        title={editing ? '编辑配置' : '新增配置'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
      >
        <Form form={form} layout="vertical" onFinish={(values) => void handleSubmit(values)}>
          <Form.Item name="configKey" label="Key" rules={[{ required: true, message: '请输入 Key' }]}>
            <Input placeholder="例如：platform.env" />
          </Form.Item>
          <Form.Item name="configValue" label="Value" rules={[{ required: true, message: '请输入 Value' }]}>
            <Input.TextArea rows={3} />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input placeholder="可选" />
          </Form.Item>
        </Form>
      </Modal>
    </>
  )
}
