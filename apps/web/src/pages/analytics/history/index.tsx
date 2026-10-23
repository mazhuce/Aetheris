import { useCallback, useEffect, useRef, useState } from 'react'
import { App, Button, Popconfirm, Select, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ClearOutlined, CopyOutlined, DeleteOutlined, HistoryOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import { clearQueryHistory, deleteQueryHistory, listQueryHistory, type QueryHistoryRecord } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'

export default function QueryHistoryPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [records, setRecords] = useState<QueryHistoryRecord[]>([])
  const [loading, setLoading] = useState(false)
  const [status, setStatus] = useState<string>()
  const [clearing, setClearing] = useState(false)
  const epoch = useRef(0)
  const pending = useRef<AbortController>()
  const pageSize = useResponsivePageSize(8, 6, 5, 3)

  const refresh = useCallback(async () => {
    const current = ++epoch.current
    pending.current?.abort()
    const controller = new AbortController()
    pending.current = controller
    setLoading(true)
    try {
      const next = await listQueryHistory(status, controller.signal)
      if (current === epoch.current && !controller.signal.aborted) setRecords(next)
    } catch {
      if (current === epoch.current && !controller.signal.aborted) message.error('查询历史加载失败')
    } finally {
      if (current === epoch.current && !controller.signal.aborted) setLoading(false)
    }
  }, [message, status])

  useEffect(() => {
    void refresh()
    return () => {
      epoch.current += 1
      pending.current?.abort()
    }
  }, [refresh])

  const copySql = async (sql: string) => {
    try {
      await navigator.clipboard.writeText(sql)
      message.success('SQL 已复制')
    } catch {
      message.error('复制失败，请手动选择复制')
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteQueryHistory(id)
      message.success('已删除')
      await refresh()
    } catch {
      message.error('删除失败')
    }
  }

  const handleClear = async () => {
    setClearing(true)
    try {
      const result = await clearQueryHistory()
      if (result.hasMore) message.info(`已删除 ${result.deleted} 条记录，仍有历史记录，可继续清理`)
      else message.success(`已清空 ${result.deleted} 条记录`)
      await refresh()
    } catch {
      message.error('清空失败')
    } finally {
      setClearing(false)
    }
  }

  const columns: TableColumnsType<QueryHistoryRecord> = [
    {
      title: '执行时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: QueryHistoryRecord['status']) =>
        value === 'success' ? <Tag color="success">成功</Tag> : <Tag color="error">失败</Tag>
    },
    {
      title: 'SQL 语句',
      dataIndex: 'sqlText',
      key: 'sqlText',
      render: (value: string, record) => (
        <div>
          <Typography.Text code style={{ fontSize: 12, display: 'block', whiteSpace: 'pre-wrap', wordBreak: 'break-all' }}>
            {value.length > 160 ? `${value.slice(0, 160)}…` : value}
          </Typography.Text>
          {record.errorMessage && (
            <Typography.Text type="danger" style={{ fontSize: 12, display: 'block', marginTop: 2 }}>
              {record.errorMessage}
            </Typography.Text>
          )}
          {record.requestId && <Typography.Text type="secondary" style={{ fontSize: 12 }}>请求编号：{record.requestId}</Typography.Text>}
        </div>
      )
    },
    {
      title: '数据源',
      dataIndex: 'datasourceName',
      key: 'datasourceName',
      width: 140,
      render: (value?: string) => value || '—'
    },
    {
      title: '行数',
      dataIndex: 'rowCount',
      key: 'rowCount',
      width: 80,
      render: (value?: number) => (value === undefined || value === null ? '—' : value.toLocaleString())
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 90,
      render: (value?: number) => (value === undefined || value === null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(1)} s` : `${value} ms`)
    },
    {
      title: '操作',
      key: 'actions',
      width: 200,
      render: (_, record) => (
        <>
          <Tooltip title="复制这条 SQL">
            <Button size="small" type="text" aria-label={`复制查询 ${record.id} 的 SQL`} icon={<CopyOutlined />} onClick={() => void copySql(record.sqlText)} />
          </Tooltip>
          <Tooltip title="去即席查询重新执行">
            <Button size="small" type="link" onClick={() => navigate('/analytics/query')}>去执行</Button>
          </Tooltip>
          <Popconfirm
            title="删除这条历史记录？"
            onConfirm={() => void handleDelete(record.id)}
          >
            <Button size="small" type="text" danger aria-label={`删除查询 ${record.id}`} icon={<DeleteOutlined />} />
          </Popconfirm>
        </>
      )
    }
  ]

  const successCount = records.filter((record) => record.status === 'success').length

  return (
    <div className="query-history-page">
      <PageHeader
        description="只展示能确认归属的本人查询记录，可复制 SQL 复用；旧版中无法确认归属的记录不在此展示。"
        extra={
          <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
        }
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              allowClear
              value={status}
              onChange={setStatus}
              className="filter-control-sm"
              options={[
                { value: 'success', label: '成功' },
                { value: 'failed', label: '失败' }
              ]}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
          <Popconfirm
            title="分批清理查询历史？"
            description="每次最多删除 500 条本人记录，删除后无法恢复。"
            okText="清理本批"
            onConfirm={() => void handleClear()}
          >
            <Button danger loading={clearing} icon={<ClearOutlined />}>清理历史</Button>
          </Popconfirm>
        </div>
      </PageToolbar>

      <DataSurface
        title="查询历史"
        description={`最近 ${records.length} 条，成功 ${successCount} 条；最多展示 200 条`}
        label="查询历史明细"
      >
        <Table<QueryHistoryRecord>
          rowKey="id"
          columns={columns}
          dataSource={records}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary"><HistoryOutlined /> 暂无查询记录。去即席查询执行一条 SQL，历史会出现在这里。</Typography.Text> }}
        />
      </DataSurface>
    </div>
  )
}
