import { useCallback, useEffect, useMemo, useState } from 'react'
import {
  App,
  Button,
  Card,
  DatePicker,
  Input,
  Modal,
  Popconfirm,
  Select,
  Space,
  Table,
  Tag
} from 'antd'
import type { TableColumnsType } from 'antd'
import type { Dayjs } from 'dayjs'
import { ExportOutlined, PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import {
  createDataSource,
  deleteDataSource,
  listDataSources,
  queryDataSource,
  testDataSource,
  updateDataSource,
  type DataSource,
  type DataSourceInput,
  type QueryResult
} from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import DataSourceForm from './components/DataSourceForm'

const statusMeta: Record<string, { color: string; text: string }> = {
  active: { color: 'success', text: '启用' },
  inactive: { color: 'default', text: '停用' }
}

export default function DataSourcePage() {
  const { message } = App.useApp()
  const [allData, setAllData] = useState<DataSource[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [type, setType] = useState<string>()
  const [status, setStatus] = useState<string>()
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [page, setPage] = useState(1)
  const [pageSize, setPageSize] = useState(10)
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataSource | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [queryOpen, setQueryOpen] = useState(false)
  const [queryRecord, setQueryRecord] = useState<DataSource | null>(null)
  const [querySql, setQuerySql] = useState('')
  const [queryLoading, setQueryLoading] = useState(false)
  const [queryResult, setQueryResult] = useState<QueryResult | null>(null)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setAllData(await listDataSources())
    } catch {
      message.error('数据源加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const filtered = useMemo(() => {
    return allData.filter((item) => {
      if (keyword && !item.name.includes(keyword) && !item.host.includes(keyword)) return false
      if (type && item.type !== type) return false
      if (status && item.status !== status) return false
      if (range && range[0] && range[1] && item.createdAt) {
        const time = new Date(item.createdAt).getTime()
        if (time < range[0].startOf('day').valueOf() || time > range[1].endOf('day').valueOf()) return false
      }
      return true
    })
  }, [allData, keyword, type, status, range])

  const paged = useMemo(
    () => filtered.slice((page - 1) * pageSize, page * pageSize),
    [filtered, page, pageSize]
  )

  const handleSubmit = async (values: DataSourceInput) => {
    setSubmitting(true)
    try {
      if (editing) {
        await updateDataSource(editing.id, values)
        message.success('数据源已更新')
      } else {
        await createDataSource(values)
        message.success('数据源已添加')
      }
      setOpen(false)
      setEditing(null)
      await refresh()
    } catch {
      message.error('保存失败，请稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteDataSource(id)
      message.success('数据源已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const handleTest = async (record: DataSource) => {
    const hide = message.loading('正在测试连接...', 0)
    try {
      const result = await testDataSource(record.id)
      if (result.success) {
        message.success(`连接成功，耗时 ${result.latencyMs ?? '—'} ms`)
      } else {
        message.error(result.message)
      }
    } catch {
      message.error('测试失败')
    } finally {
      hide()
    }
  }

  const openQuery = (record: DataSource) => {
    setQueryRecord(record)
    setQuerySql('')
    setQueryResult(null)
    setQueryOpen(true)
  }

  const runQuery = async () => {
    if (!queryRecord || !querySql.trim()) return
    setQueryLoading(true)
    try {
      setQueryResult(await queryDataSource(queryRecord.id, querySql))
    } catch {
      message.error('查询失败')
    } finally {
      setQueryLoading(false)
    }
  }

  const columns: TableColumnsType<DataSource> = [
    { title: '名称', dataIndex: 'name', key: 'name', width: 160 },
    { title: '类型', dataIndex: 'type', key: 'type', width: 110, render: (value: string) => <Tag>{value}</Tag> },
    { title: '主机地址', dataIndex: 'host', key: 'host', width: 140 },
    { title: '端口', dataIndex: 'port', key: 'port', width: 80, render: (value?: number) => value ?? '—' },
    { title: '数据库', dataIndex: 'databaseName', key: 'databaseName', width: 120, render: (value?: string) => value || '—' },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 110, render: (value?: string) => value || '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 80,
      render: (value?: string) => {
        const meta = statusMeta[value ?? ''] ?? { color: 'default', text: value ?? '—' }
        return <Tag color={meta.color}>{meta.text}</Tag>
      }
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (value?: string) => formatDateTime(value) },
    {
      title: '操作',
      key: 'action',
      width: 240,
      fixed: 'right',
      render: (_, record) => (
        <Space size={0}>
          <Button type="link" size="small" onClick={() => void handleTest(record)}>
            测试连接
          </Button>
          <Button type="link" size="small" onClick={() => openQuery(record)}>
            查询
          </Button>
          <Button
            type="link"
            size="small"
            onClick={() => {
              setEditing(record)
              setOpen(true)
            }}
          >
            编辑
          </Button>
          <Popconfirm title="确定删除该数据源？" onConfirm={() => void handleDelete(record.id)}>
            <Button type="link" size="small" danger>
              删除
            </Button>
          </Popconfirm>
        </Space>
      )
    }
  ]

  return (
    <>
      <PageHeader
        title="数据源管理"
        description="统一管理平台接入的各类数据源，支持 PostgreSQL、MySQL 等常见数据库。"
        extra={
          <Space>
            <Button icon={<ExportOutlined />} onClick={() => message.info('导出功能规划中')}>
              导出
            </Button>
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => {
                setEditing(null)
                setOpen(true)
              }}
            >
              新增数据源
            </Button>
          </Space>
        }
      />

      <Card size="small" style={{ marginBottom: 16 }}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/主机</span>
            <Input
              placeholder="搜索名称或主机"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              allowClear
              style={{ width: 180 }}
            />
          </div>
          <div className="filter-item">
            <span>类型</span>
            <Select
              value={type}
              onChange={setType}
              allowClear
              placeholder="全部"
              style={{ width: 130 }}
              options={[
                { value: 'postgresql', label: 'PostgreSQL' },
                { value: 'mysql', label: 'MySQL' }
              ]}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              value={status}
              onChange={setStatus}
              allowClear
              placeholder="全部"
              style={{ width: 110 }}
              options={[
                { value: 'active', label: '启用' },
                { value: 'inactive', label: '停用' }
              ]}
            />
          </div>
          <div className="filter-item">
            <span>创建时间</span>
            <DatePicker.RangePicker value={range} onChange={setRange} />
          </div>
          <Button type="primary" onClick={() => setPage(1)}>
            查询
          </Button>
          <Button
            onClick={() => {
              setKeyword('')
              setType(undefined)
              setStatus(undefined)
              setRange(null)
              setPage(1)
            }}
          >
            重置
          </Button>
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>
            刷新
          </Button>
        </div>
      </Card>

      <Card size="small">
        <Table<DataSource>
          size="small"
          bordered
          rowKey="id"
          columns={columns}
          dataSource={paged}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{
            current: page,
            pageSize,
            total: filtered.length,
            showSizeChanger: true,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (nextPage, nextSize) => {
              setPage(nextPage)
              setPageSize(nextSize)
            }
          }}
          locale={{ emptyText: '暂无数据源，点击右上角「新增数据源」开始。' }}
        />
      </Card>

      <DataSourceForm
        open={open}
        editing={editing}
        submitting={submitting}
        onCancel={() => {
          setOpen(false)
          setEditing(null)
        }}
        onSubmit={(values) => void handleSubmit(values)}
      />

      <Modal
        title={`SQL 查询 · ${queryRecord?.name ?? ''}`}
        open={queryOpen}
        onCancel={() => setQueryOpen(false)}
        footer={null}
        width={820}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Input.TextArea
            value={querySql}
            onChange={(event) => setQuerySql(event.target.value)}
            placeholder="输入 SELECT / SHOW / EXPLAIN 语句"
            autoSize={{ minRows: 3, maxRows: 8 }}
          />
          <Button type="primary" loading={queryLoading} onClick={() => void runQuery()}>
            执行
          </Button>
          {queryResult && (
            <Table
              size="small"
              bordered
              scroll={{ x: 'max-content' }}
              pagination={false}
              columns={queryResult.columns.map((column) => ({
                title: column,
                dataIndex: column,
                key: column,
                ellipsis: true
              }))}
              dataSource={queryResult.rows.map((row, rowIndex) => {
                const record: Record<string, unknown> = { key: rowIndex }
                queryResult.columns.forEach((column, columnIndex) => {
                  record[column] = row[columnIndex]
                })
                return record
              })}
            />
          )}
        </Space>
      </Modal>
    </>
  )
}
