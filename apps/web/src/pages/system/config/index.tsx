import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Modal, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import RowActions from '@/components/RowActions'
import {
  createConfig,
  deleteConfig,
  listConfigs,
  updateConfig,
  type SystemConfig,
  type SystemConfigInput
} from '@/api/system'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

export default function SystemConfigPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
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
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) }
  ]

  if (canManage) columns.push({
      title: '操作',
      key: 'action',
      width: 56,
      align: 'right',
      render: (_, record) => (
        <RowActions items={[
          { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
          {
            key: 'delete',
            label: '删除',
            danger: true,
            onClick: () => modal.confirm({
              title: `删除配置“${record.configKey}”？`,
              content: '删除可能影响依赖该配置的服务，请确认该配置已不再使用。',
              okText: '确认删除',
              okButtonProps: { danger: true },
              onOk: () => handleDelete(record.id)
            })
          }
        ]} />
      )
    })

  return (
    <>
      <PageHeader
        description="维护平台运行所需的全局配置项。"
        extra={canManage ? (
          <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
            新增配置
          </Button>
        ) : <Tag>只读访问</Tag>}
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>Key/Value</span>
            <Input placeholder="搜索 Key 或 Value" value={keyword} onChange={(e) => setKeyword(e.target.value)} allowClear className="filter-control-3xl" />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>

      <DataSurface label="系统配置列表">
        <Table<SystemConfig>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: '暂无配置' }}
        />
      </DataSurface>

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
