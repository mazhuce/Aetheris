import { useCallback, useEffect, useState } from 'react'
import { App, Button, Modal, Popconfirm, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { DeleteOutlined, PlusOutlined, ReloadOutlined, TagsOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { addTableTag, listTableTagGroups, listTables, removeTableTag, type TableTagGroup } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { getCurrentRole } from '@/utils/access'
import { useAuthSession } from '@/hooks/useAuthSession'

interface TagTableRow {
  tag: string
  tableId: number
  tableTagId: number
  tableName: string
  createdBy?: string
  createdAt?: string
}

export default function TagAssetsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [groups, setGroups] = useState<TableTagGroup[]>([])
  const [loading, setLoading] = useState(false)
  const [selectedTag, setSelectedTag] = useState<string>()
  const [modalOpen, setModalOpen] = useState(false)
  const [form, setForm] = useState<{ tableId?: number; tag?: string }>({})
  const [submitting, setSubmitting] = useState(false)

  const session = useAuthSession()
  const username = session.status === 'authenticated' ? session.user.username : undefined
  const isAdmin = getCurrentRole() === 'admin'

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setGroups(await listTableTagGroups())
    } catch {
      message.error('标签资产加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 展平成行：选中标签时只显示该标签的表，否则显示全部。 */
  const rows: TagTableRow[] = (selectedTag
    ? groups.filter((group) => group.tag === selectedTag)
    : groups
  ).flatMap((group) =>
    group.tables.map((table) => ({
      tag: group.tag,
      tableId: table.tableId,
      tableTagId: table.id,
      tableName: table.tableName,
      createdBy: table.createdBy,
      createdAt: table.createdAt
    }))
  )

  const handleAdd = async () => {
    if (!form.tableId || !form.tag?.trim()) {
      message.warning('请选择表并填写标签名')
      return
    }
    setSubmitting(true)
    try {
      await addTableTag(form.tableId, form.tag.trim())
      message.success('标签已添加')
      setModalOpen(false)
      setForm({})
      await refresh()
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '添加失败')
    } finally {
      setSubmitting(false)
    }
  }

  const handleRemove = async (row: TagTableRow) => {
    try {
      await removeTableTag(row.tableTagId)
      message.success('已删除标签')
      await refresh()
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      message.error(detail || '删除失败')
    }
  }

  const columns: TableColumnsType<TagTableRow> = [
    {
      title: '标签',
      dataIndex: 'tag',
      key: 'tag',
      width: 140,
      render: (value: string) => <Tag color="geekblue" icon={<TagsOutlined />}>{value}</Tag>
    },
    {
      title: '表',
      key: 'tableName',
      render: (row: TagTableRow) => (
        <Typography.Text strong style={{ fontSize: 13 }}>{row.tableName}</Typography.Text>
      )
    },
    { title: '打标人', dataIndex: 'createdBy', key: 'createdBy', width: 110, render: (v?: string) => v ? <Typography.Text code>{v}</Typography.Text> : '—' },
    { title: '打标时间', dataIndex: 'createdAt', key: 'createdAt', width: 160, render: (v?: string) => formatDateTime(v) },
    {
      title: '操作',
      key: 'actions',
      width: 180,
      render: (_, row) => (
        <>
          <Button size="small" type="link" onClick={() => navigate('/assets/tables')}>表详情</Button>
          {(isAdmin || row.createdBy === username) && (
            <Popconfirm title={`移除表「${row.tableName}」的「${row.tag}」标签？`} onConfirm={() => void handleRemove(row)}>
              <Button size="small" type="text" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          )}
        </>
      )
    }
  ]

  const totalTags = groups.length
  const totalTaggedTables = new Set(groups.flatMap((group) => group.tables.map((table) => table.tableId))).size

  return (
    <div className="tag-assets-page">
      <PageHeader
        description="标签资产：用业务语言为数据表打标（如「核心表」「敏感」「待归档」），按标签分组浏览资产。普通用户可打标并删除自己创建的标签，管理员可删除全部。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(220px, 1fr))', gap: 16, marginBottom: 16 }}>
        <StatCard title="标签总数" value={String(totalTags)} icon={<TagsOutlined />} footer="不同标签名" />
        <StatCard title="已打标表" value={String(totalTaggedTables)} icon={<TagsOutlined />} footer="至少有一个标签" tone="green" />
        <StatCard title="标签关系数" value={String(rows.length)} icon={<TagsOutlined />} footer="表×标签组合数" />
      </div>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>标签</span>
            <Select
              placeholder="全部标签"
              allowClear
              value={selectedTag}
              onChange={setSelectedTag}
              className="filter-control-sm"
              style={{ minWidth: 180 }}
              options={groups.map((group) => ({ value: group.tag, label: `${group.tag}（${group.tableCount}）` }))}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
          <Button icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>打标签</Button>
        </div>
      </PageToolbar>

      <DataSurface title="标签资产清单" description={selectedTag ? `标签「${selectedTag}」下的表` : '全部标签关系'} label="标签资产明细">
        <Table<TagTableRow>
          rowKey="tableTagId"
          columns={columns}
          dataSource={rows}
          loading={loading}
          scroll={{ x: 900 }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary">暂无标签。点击「打标签」为数据表添加业务标注。</Typography.Text> }}
        />
      </DataSurface>

      <Modal
        title="为数据表打标签"
        open={modalOpen}
        confirmLoading={submitting}
        onOk={() => void handleAdd()}
        onCancel={() => setModalOpen(false)}
        okText="添加"
      >
        <AddTagForm value={form} onChange={setForm} />
      </Modal>
    </div>
  )
}

/** 打标表单：表选择器（按名称搜索）+ 标签名。独立组件避免主组件重渲染时表单状态抖动。 */
function AddTagForm({ value, onChange }: { value: { tableId?: number; tag?: string }, onChange: (next: { tableId?: number; tag?: string }) => void }) {
  const [tables, setTables] = useState<{ id: number; tableName: string }[]>([])

  useEffect(() => {
    void listTables().then((list) => setTables(list.map((table) => ({ id: table.id, tableName: table.tableName })))).catch(() => undefined)
  }, [])

  return (
    <div style={{ display: 'grid', gap: 12, paddingTop: 8 }}>
      <div>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>目标表</Typography.Text>
        <Select
          showSearch
          optionFilterProp="label"
          placeholder="搜索并选择数据表"
          style={{ width: '100%' }}
          value={value.tableId}
          onChange={(tableId) => onChange({ ...value, tableId })}
          options={tables.map((table) => ({ value: table.id, label: table.tableName }))}
        />
      </div>
      <div>
        <Typography.Text type="secondary" style={{ display: 'block', marginBottom: 4 }}>标签名（如：核心表、敏感、待归档）</Typography.Text>
        <Select
          showSearch
          allowClear
          mode="tags"
          maxCount={1}
          placeholder="选择已有标签或输入新标签"
          style={{ width: '100%' }}
          value={value.tag ? [value.tag] : []}
          onChange={(tags: string[]) => onChange({ ...value, tag: tags[0] })}
          tokenSeparators={[',']}
        />
      </div>
    </div>
  )
}
