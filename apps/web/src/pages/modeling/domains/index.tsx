import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Form, Input, Modal, Row, Select, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { AppstoreOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import RowActions from '@/components/RowActions'
import StatusBadge from '@/components/StatusBadge'
import {
  createBusinessDomain,
  deleteBusinessDomain,
  listBusinessDomains,
  listTables,
  updateBusinessDomain,
  type BusinessDomain,
  type BusinessDomainInput,
  type MetadataTable
} from '@/api/metadata'
import { canManagePlatform } from '@/utils/access'
import { formatDateTime } from '@/utils/format'

export default function DomainPage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [domains, setDomains] = useState<BusinessDomain[]>([])
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string>()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<BusinessDomain | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [form] = Form.useForm<BusinessDomainInput>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [nextDomains, nextTables] = await Promise.all([listBusinessDomains(), listTables()])
      setDomains(nextDomains)
      setTables(nextTables)
    } catch { message.error('业务域加载失败') } finally { setLoading(false) }
  }, [message])

  useEffect(() => { void refresh() }, [refresh])
  useEffect(() => {
    if (!open) return
    if (editing) form.setFieldsValue(editing)
    else { form.resetFields(); form.setFieldsValue({ status: 'active' }) }
  }, [editing, form, open])

  const rows = useMemo(() => domains
    .filter((item) => !keyword || item.domainCode.toLowerCase().includes(keyword.toLowerCase()) || item.domainName.includes(keyword))
    .filter((item) => !status || item.status === status)
    .map((item) => ({ ...item, tableCount: tables.filter((table) => table.businessDomainId === item.id).length })),
  [domains, keyword, status, tables])

  const save = async (values: BusinessDomainInput) => {
    setSubmitting(true)
    try {
      if (editing) await updateBusinessDomain(editing.id, values)
      else await createBusinessDomain(values)
      message.success(editing ? '业务域已更新' : '业务域已创建')
      setOpen(false); setEditing(null); await refresh()
    } catch (error: any) { message.error(error.response?.data?.message || '业务域保存失败') }
    finally { setSubmitting(false) }
  }

  const columns: TableColumnsType<BusinessDomain & { tableCount: number }> = [
    { title: '业务域编码', dataIndex: 'domainCode', width: 180, render: (value) => <Typography.Text code>{value}</Typography.Text> },
    { title: '业务域名称', dataIndex: 'domainName', width: 180, render: (value) => <Typography.Text strong>{value}</Typography.Text> },
    { title: '负责人', dataIndex: 'owner', width: 120, render: (value) => value || '—' },
    { title: '纳管数据表', dataIndex: 'tableCount', width: 110, render: (value) => <Tag>{value} 张</Tag> },
    { title: '状态', dataIndex: 'status', width: 100, render: (value) => <StatusBadge tone={value === 'active' ? 'success' : 'neutral'}>{value === 'active' ? '启用' : '停用'}</StatusBadge> },
    { title: '说明', dataIndex: 'description', ellipsis: true, render: (value) => value || '—' },
    { title: '更新时间', dataIndex: 'updatedAt', width: 170, render: formatDateTime }
  ]
  if (canManage) columns.push({ title: '操作', width: 56, align: 'right', render: (_, record) => (
    <RowActions items={[
      { key: 'edit', label: '编辑', onClick: () => { setEditing(record); setOpen(true) } },
      ...(record.domainCode === 'UNASSIGNED' ? [] : [{ key: 'delete', label: '删除', danger: true, onClick: () => modal.confirm({
        title: `删除业务域“${record.domainName}”？`, content: '仅未被任何资产引用的业务域可以删除。', okButtonProps: { danger: true },
        onOk: async () => { try { await deleteBusinessDomain(record.id); message.success('业务域已删除'); await refresh() } catch (error: any) { message.error(error.response?.data?.message || '删除失败') } }
      }) }])
    ]} />
  ) })

  const assigned = tables.filter((table) => table.businessDomainId).length
  return <>
    <PageHeader description="以稳定业务域统一组织数据表、逻辑模型、维度和指标；编码作为跨系统契约，名称可随组织语言演进。" extra={canManage ? <Button type="primary" icon={<PlusOutlined />} onClick={() => { setEditing(null); setOpen(true) }}>新建业务域</Button> : <Tag>只读访问</Tag>} />
    <Row gutter={12} className="stat-row">
      <Col span={8}><StatCard title="业务域" value={String(domains.filter((item) => item.domainCode !== 'UNASSIGNED').length)} icon={<AppstoreOutlined />} /></Col>
      <Col span={8}><StatCard title="已归属数据表" value={String(assigned)} icon={<AppstoreOutlined />} tone="green" /></Col>
      <Col span={8}><StatCard title="待归属数据表" value={String(tables.length - assigned)} icon={<AppstoreOutlined />} tone="orange" /></Col>
    </Row>
    <PageToolbar><div className="filter-bar">
      <div className="filter-item"><span>名称/编码</span><Input allowClear value={keyword} onChange={(event) => setKeyword(event.target.value)} placeholder="搜索业务域" className="filter-control-3xl" /></div>
      <div className="filter-item"><span>状态</span><Select allowClear value={status} onChange={setStatus} placeholder="全部状态" className="filter-control-lg" options={[{ value: 'active', label: '启用' }, { value: 'disabled', label: '停用' }]} /></div>
      <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
    </div></PageToolbar>
    <DataSurface title="业务域目录" description="删除前必须先迁移所有关联资产" label="业务域目录">
      <Table rowKey="id" size="small" loading={loading} columns={columns} dataSource={rows} scroll={{ x: 1050 }} pagination={{ pageSize: 10, showTotal: (total) => `共 ${total} 个业务域` }} />
    </DataSurface>
    <Modal title={editing ? '编辑业务域' : '新建业务域'} open={open} onCancel={() => { setOpen(false); setEditing(null) }} onOk={() => form.submit()} confirmLoading={submitting} width={620}>
      <Form form={form} layout="vertical" onFinish={(values) => void save(values)}>
        <Space align="start" style={{ width: '100%' }} size={12}>
          <Form.Item name="domainCode" label="业务域编码" rules={[{ required: true }, { pattern: /^[A-Za-z][A-Za-z0-9_]{1,63}$/, message: '以字母开头，仅支持字母、数字和下划线' }]}><Input disabled={Boolean(editing)} placeholder="如 TRADE" style={{ width: 260 }} /></Form.Item>
          <Form.Item name="domainName" label="业务域名称" rules={[{ required: true }]}><Input placeholder="如 交易域" style={{ width: 260 }} /></Form.Item>
        </Space>
        <Space align="start" style={{ width: '100%' }} size={12}>
          <Form.Item name="owner" label="负责人"><Input placeholder="账号或姓名" style={{ width: 260 }} /></Form.Item>
          <Form.Item name="status" label="状态"><Select style={{ width: 260 }} options={[{ value: 'active', label: '启用' }, { value: 'disabled', label: '停用' }]} /></Form.Item>
        </Space>
        <Form.Item name="description" label="业务边界说明"><Input.TextArea rows={3} maxLength={512} showCount placeholder="说明该业务域包含和不包含的业务范围" /></Form.Item>
      </Form>
    </Modal>
  </>
}
