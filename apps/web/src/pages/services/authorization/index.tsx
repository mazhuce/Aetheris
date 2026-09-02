import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, DatePicker, Form, Input, Modal, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { SafetyCertificateOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
import {
  createGrant,
  deleteGrant,
  listGrants,
  revokeGrant,
  updateGrant,
  type ServiceGrant
} from '@/api/serving'
import { formatDateTime } from '@/utils/format'
import { canManagePlatform } from '@/utils/access'

const grantTypeMeta: Record<string, { label: string; color: string }> = {
  user: { label: '用户', color: 'blue' },
  role: { label: '角色', color: 'purple' }
}

const scopeMeta: Record<string, { label: string; color: string }> = {
  read: { label: '只读', color: 'cyan' },
  write: { label: '读写', color: 'gold' }
}

const statusMeta: Record<string, { label: string; tone: 'success' | 'neutral' }> = {
  active: { label: '生效中', tone: 'success' },
  revoked: { label: '已撤销', tone: 'neutral' }
}

interface GrantFormValues {
  apiPath: string
  grantee: string
  grantType?: string
  scope?: string
  expiresAt?: Dayjs | null
}

export default function ServiceAuthorizationPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<ServiceGrant[]>([])
  const [loading, setLoading] = useState(false)
  const [statusFilter, setStatusFilter] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<ServiceGrant | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<GrantFormValues>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listGrants())
    } catch {
      message.error('授权列表加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  useEffect(() => {
    if (open) {
      if (editing) {
        form.setFieldsValue({
          ...editing,
          expiresAt: editing.expiresAt ? dayjs(editing.expiresAt) : null
        })
      } else {
        form.resetFields()
      }
    }
  }, [open, editing, form])

  const filtered = useMemo(() => allData.filter((item) => !statusFilter || item.status === statusFilter), [allData, statusFilter])

  const stats = useMemo(
    () => ({
      total: allData.length,
      active: allData.filter((item) => item.status === 'active').length,
      paths: new Set(allData.map((item) => item.apiPath)).size
    }),
    [allData]
  )

  const handleSubmit = async (values: GrantFormValues) => {
    setSubmitting(true)
    try {
      const payload = {
        apiPath: values.apiPath.trim(),
        grantee: values.grantee.trim(),
        grantType: values.grantType,
        scope: values.scope,
        expiresAt: values.expiresAt ? values.expiresAt.toISOString() : null
      }
      if (editing) {
        await updateGrant(editing.id, payload)
        message.success('授权已更新')
      } else {
        await createGrant(payload)
        message.success('授权已创建')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '保存失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (record: ServiceGrant) => {
    try {
      await deleteGrant(record.id)
      message.success('授权已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const handleRevoke = async (record: ServiceGrant) => {
    try {
      await revokeGrant(record.id)
      message.success('授权已撤销')
      await refresh()
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '撤销失败')
    }
  }

  /** 授权是否已过期：仅对未撤销且设定了到期时间的记录有意义。 */
  const isExpired = (record?: ServiceGrant) => {
    if (!record?.expiresAt || record.status !== 'active') return false
    return dayjs(record.expiresAt).isBefore(dayjs())
  }

  const columns: TableColumnsType<ServiceGrant> = [
    {
      title: '接口路径',
      dataIndex: 'apiPath',
      key: 'apiPath',
      width: 280,
      ellipsis: true,
      render: (v: string) => <Typography.Text copyable={{ text: v }}>{v}</Typography.Text>
    },
    { title: '被授权方', dataIndex: 'grantee', key: 'grantee', width: 140 },
    {
      title: '授权类型',
      dataIndex: 'grantType',
      key: 'grantType',
      width: 90,
      render: (v?: string) => {
        const meta = v ? grantTypeMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    {
      title: '权限范围',
      dataIndex: 'scope',
      key: 'scope',
      width: 90,
      render: (v?: string) => {
        const meta = v ? scopeMeta[v] : undefined
        return meta ? <Tag color={meta.color}>{meta.label}</Tag> : '—'
      }
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 110,
      render: (v?: string, record?) => {
        const meta = v ? statusMeta[v] : undefined
        if (!meta) return '—'
        if (meta.label === '生效中' && isExpired(record)) {
          return <StatusBadge tone="warning">已过期</StatusBadge>
        }
        return <StatusBadge tone={meta.tone}>{meta.label}</StatusBadge>
      }
    },
    { title: '到期时间', dataIndex: 'expiresAt', key: 'expiresAt', width: 170, render: (v?: string) => (v ? formatDateTime(v) : '长期有效') },
    { title: '授权人', dataIndex: 'grantedBy', key: 'grantedBy', width: 100, render: (v?: string) => v || '—' },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (v?: string) => formatDateTime(v) }
  ]

  if (canManage) {
    columns.push({
      title: '操作',
      key: 'action',
      width: 100,
      align: 'right',
      render: (_, record) => (
        <RowActions
          items={[
            ...(record.status === 'active'
              ? [
                  {
                    key: 'revoke',
                    label: '撤销',
                    danger: true,
                    onClick: () =>
                      modal.confirm({
                        title: `撤销对“${record.grantee}”的授权？`,
                        content: '撤销后该被授权方将立即失去此接口的访问权限。',
                        okText: '确认撤销',
                        okButtonProps: { danger: true },
                        onOk: () => handleRevoke(record)
                      })
                  }
                ]
              : []),
            { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
            {
              key: 'delete',
              label: '删除',
              danger: true,
              onClick: () =>
                modal.confirm({
                  title: `删除授权记录“${record.grantee} @ ${record.apiPath}”？`,
                  content: '删除后该授权记录将不可恢复。',
                  okText: '确认删除',
                  okButtonProps: { danger: true },
                  onOk: () => handleDelete(record)
                })
            }
          ]}
        />
      )
    })
  }

  return (
    <>
      <PageHeader
        description="配置数据服务的使用授权与访问范围，控制谁能用、能做什么、用到何时。"
        extra={
          canManage ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>
              新增授权
            </Button>
          ) : (
            <Tag>只读访问</Tag>
          )
        }
      />
      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard title="授权总数" value={String(stats.total)} icon={<SafetyCertificateOutlined />} />
        </Col>
        <Col span={8}>
          <StatCard title="生效中" value={String(stats.active)} icon={<SafetyCertificateOutlined />} tone="green" />
        </Col>
        <Col span={8}>
          <StatCard title="覆盖接口" value={String(stats.paths)} icon={<SafetyCertificateOutlined />} tone="orange" />
        </Col>
      </Row>
      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>状态</span>
            <Select
              allowClear
              placeholder="全部状态"
              value={statusFilter}
              onChange={setStatusFilter}
              className="filter-control-xl"
              options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        </div>
      </PageToolbar>
      <DataSurface label="服务授权列表">
        <Table<ServiceGrant>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={filtered}
          loading={loading}
          scroll={{ x: 1250 }}
          pagination={{ pageSize: 10, showTotal: (t) => `共 ${t} 条` }}
          locale={{ emptyText: '暂无授权记录' }}
        />
      </DataSurface>
      <Modal
        title={editing ? '编辑授权' : '新增授权'}
        open={open}
        onCancel={() => { setOpen(false); setEditing(null) }}
        onOk={() => form.submit()}
        confirmLoading={submitting}
        width={560}
      >
        <Form form={form} layout="vertical" initialValues={{ grantType: 'user', scope: 'read' }} onFinish={(v) => void handleSubmit(v)}>
          <Form.Item name="apiPath" label="接口路径" rules={[{ required: true, message: '请输入接口路径' }]}>
            <Input placeholder="如 /api/serving/orders/detail" />
          </Form.Item>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="grantee" label="被授权方" rules={[{ required: true, message: '请输入被授权方' }]}>
                <Input placeholder="用户名或角色名" />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="grantType" label="授权类型">
                <Select options={Object.entries(grantTypeMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
          </Row>
          <Row gutter={12}>
            <Col span={12}>
              <Form.Item name="scope" label="权限范围">
                <Select options={Object.entries(scopeMeta).map(([value, meta]) => ({ value, label: meta.label }))} />
              </Form.Item>
            </Col>
            <Col span={12}>
              <Form.Item name="expiresAt" label="到期时间">
                <DatePicker showTime style={{ width: '100%' }} placeholder="留空表示长期有效" />
              </Form.Item>
            </Col>
          </Row>
        </Form>
      </Modal>
    </>
  )
}
