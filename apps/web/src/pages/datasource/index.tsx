import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import {
  App,
  Alert,
  Button,
  Card,
  Col,
  DatePicker,
  Input,
  Modal,
  Row,
  Select,
  Space,
  Statistic,
  Table,
  Tag,
  Tooltip
} from 'antd'
import type { TableColumnsType } from 'antd'
import type { Dayjs } from 'dayjs'
import {
  ApiOutlined,
  ClusterOutlined,
  DatabaseOutlined,
  PlusOutlined,
  ReloadOutlined,
  ThunderboltOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import RowActions from '@/components/RowActions'
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
import { canManagePlatform } from '@/utils/access'
import DataSourceForm from './components/DataSourceForm'
import DataSourceBrowser from './components/DataSourceBrowser'
import ConnectionHealthStatus from './components/ConnectionHealthStatus'
import { connectionFailureDescription, effectiveConnectionStatus } from './connectionHealth'
import { useConnectionHealthClock } from './useConnectionHealthClock'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'
import { dataSourceOptions, getDataSourceDefinition } from './sourceCatalog'

const statusMeta: Record<string, { color: string; text: string }> = {
  active: { color: 'success', text: '启用' },
  inactive: { color: 'default', text: '停用' }
}

export default function DataSourcePage() {
  const { message, modal } = App.useApp()
  const canManage = canManagePlatform()
  const [allData, setAllData] = useState<DataSource[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')
  const [type, setType] = useState<string>()
  const [status, setStatus] = useState<string>()
  const [range, setRange] = useState<[Dayjs | null, Dayjs | null] | null>(null)
  const [page, setPage] = useState(1)
  const pageSize = useResponsivePageSize()
  const [open, setOpen] = useState(false)
  const [editing, setEditing] = useState<DataSource | null>(null)
  const [submitting, setSubmitting] = useState(false)
  const [queryOpen, setQueryOpen] = useState(false)
  const [queryRecord, setQueryRecord] = useState<DataSource | null>(null)
  const [querySql, setQuerySql] = useState('')
  const [queryLoading, setQueryLoading] = useState(false)
  const [queryResult, setQueryResult] = useState<QueryResult | null>(null)
  const [browserOpen, setBrowserOpen] = useState(false)
  const [browserRecord, setBrowserRecord] = useState<DataSource | null>(null)
  const pendingProbes = useRef(new Set<number>())
  const [testingIds, setTestingIds] = useState(new Set<number>())
  const healthNow = useConnectionHealthClock(allData)

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

  useEffect(() => {
    setPage(1)
  }, [pageSize])

  const overview = useMemo(() => ({
    total: allData.length,
    active: allData.filter((item) => item.status === 'active').length,
    lakehouse: allData.filter((item) => item.type === 'hive2' || item.type === 'spark').length,
    types: new Set(allData.map((item) => item.type)).size
  }), [allData])

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
    if (pendingProbes.current.has(record.id)) return
    pendingProbes.current.add(record.id)
    setTestingIds(new Set(pendingProbes.current))
    try {
      const result = await testDataSource(record.id)
      setAllData((items) => items.map((item) => item.id === record.id
        ? { ...item, connectionHealth: result.connectionHealth ?? { status: 'unknown' } }
        : item))
      if (result.success) {
        message.success(`连接探测成功 · ${result.latencyMs ?? '—'} ms`)
      } else if (result.connectionHealth?.status === 'checking') {
        message.info('已有连接探测正在执行，稍后刷新可查看结果')
      } else {
        message.error(connectionFailureDescription(result.connectionHealth))
      }
    } catch (error) {
      const statusCode = (error as { response?: { status?: number } }).response?.status
      if (statusCode === 401 || statusCode === 403) {
        message.error('没有测试此数据源连接的权限，请检查登录状态与数据源查询授权')
      } else {
        // A missing response cannot establish either database success or failure.
        setAllData((items) => items.map((item) => item.id === record.id ? {
          ...item,
          connectionHealth: {
            ...item.connectionHealth,
            status: 'unknown',
            failureCode: 'PROBE_REQUEST_FAILED',
            failureMessage: '未取得探测结果，请检查平台服务与网络后刷新或重试。'
          }
        } : item))
        message.error('未取得探测结果，连接状态未知；请检查平台服务与网络')
      }
    } finally {
      pendingProbes.current.delete(record.id)
      setTestingIds(new Set(pendingProbes.current))
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
    } catch (error) {
      const responseData = (error as { response?: { data?: { message?: unknown } } }).response?.data
      const apiMessage = typeof responseData?.message === 'string' ? responseData.message : null
      message.error(apiMessage ?? '查询失败')
    } finally {
      setQueryLoading(false)
    }
  }

  const columns: TableColumnsType<DataSource> = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      width: 190,
      render: (value: string, record) => (
        <Space size={6}>
          <span className="entity-name" title={value}>{value}</span>
          {record.managed && <Tooltip title="由系统自动维护配置"><Tag>系统</Tag></Tooltip>}
        </Space>
      )
    },
    {
      title: '类型',
      dataIndex: 'type',
      key: 'type',
      width: 145,
      render: (value: string) => {
        const definition = getDataSourceDefinition(value)
        return <Tag>{definition.label}</Tag>
      }
    },
    { title: '主机地址', dataIndex: 'host', key: 'host', width: 140 },
    { title: '端口', dataIndex: 'port', key: 'port', width: 80, render: (value?: number) => value ?? '—' },
    { title: '数据库', dataIndex: 'databaseName', key: 'databaseName', width: 120, render: (value?: string) => value || '—' },
    { title: '用户名', dataIndex: 'username', key: 'username', width: 110, render: (value?: string) => value || '—' },
    {
      title: '配置状态',
      dataIndex: 'status',
      key: 'status',
      width: 100,
      render: (value?: string) => {
        const meta = statusMeta[value ?? ''] ?? { color: 'default', text: value ?? '—' }
        return <StatusBadge tone="neutral" dot={false}>{meta.text}</StatusBadge>
      }
    },
    {
      title: '连接健康',
      key: 'connectionHealth',
      width: 320,
      render: (_, record) => <ConnectionHealthStatus health={record.connectionHealth} now={healthNow} testing={testingIds.has(record.id)} />
    },
    { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt', width: 170, render: (value?: string) => formatDateTime(value) },
    { title: '更新时间', dataIndex: 'updatedAt', key: 'updatedAt', width: 170, render: (value?: string) => formatDateTime(value) }
  ]

  columns.push({
      title: '操作',
      key: 'action',
      width: 140,
      align: 'right',
      fixed: 'right',
      render: (_, record) => (
        <RowActions
          primary={(
            <Tooltip title="执行轻量连接探测，不采集库表；需要数据源查询权限">
              <Button type="text" size="small" icon={<ThunderboltOutlined />} loading={testingIds.has(record.id)} disabled={effectiveConnectionStatus(record.connectionHealth, healthNow) === 'checking'} aria-label={`测试数据源 ${record.name}`} onClick={() => void handleTest(record)}>测试连接</Button>
            </Tooltip>
          )}
          items={canManage ? [
            { key: 'query', label: 'SQL 查询', onClick: () => openQuery(record) },
            { key: 'browse', label: '浏览库表', onClick: () => { setBrowserRecord(record); setBrowserOpen(true) } },
            { key: 'edit', label: '编辑', disabled: record.managed || testingIds.has(record.id), onClick: () => { setEditing(record); setOpen(true) } },
            {
              key: 'delete',
              label: '删除',
              danger: true,
              disabled: record.managed || testingIds.has(record.id),
              onClick: () => modal.confirm({
                title: `删除数据源“${record.name}”？`,
                content: '删除后平台将无法继续访问该连接，此操作不可恢复。',
                okText: '确认删除',
                okButtonProps: { danger: true },
                onOk: () => handleDelete(record.id)
              })
            }
          ] : []}
        />
      )
    })

  return (
    <>
      <PageHeader
        description="统一管理关系型数据库、分布式数据库与 Hive 数仓的 JDBC 连接。"
        extra={canManage ? (
          <Space>
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
        ) : <Tag>只读访问</Tag>}
      />

      <Row gutter={[16, 16]} className="datasource-overview">
        <Col xs={12} lg={6}><Card><Statistic title="已接入数据源" value={overview.total} prefix={<DatabaseOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="配置已启用" value={overview.active} prefix={<ApiOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="Hive / 湖仓" value={overview.lakehouse} prefix={<ClusterOutlined />} /></Card></Col>
        <Col xs={12} lg={6}><Card><Statistic title="已覆盖类型" value={overview.types} suffix="/ 13" prefix={<ApiOutlined />} /></Card></Col>
      </Row>

      <Alert
        type="info"
        showIcon
        className="form-section-space"
        message="配置启用不代表连接可用。连接健康来自手动轻量探测，结果过期后显示未知；最近成功时间保留为历史记录。"
        description="打开或刷新此页只读取已保存的状态。点击“测试连接”才发起连接探测；库表采集与业务查询耗时单独查看。"
      />

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>名称/主机</span>
            <Input
              placeholder="搜索名称或主机"
              value={keyword}
              onChange={(event) => setKeyword(event.target.value)}
              allowClear
              className="filter-control-2xl"
            />
          </div>
          <div className="filter-item">
            <span>类型</span>
            <Select
              value={type}
              onChange={setType}
              allowClear
              placeholder="全部"
              className="filter-control-xl"
              options={dataSourceOptions}
            />
          </div>
          <div className="filter-item">
            <span>配置状态</span>
            <Select
              value={status}
              onChange={setStatus}
              allowClear
              placeholder="全部"
              className="filter-control-xs"
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
          <Button icon={<ReloadOutlined />} disabled={testingIds.size > 0} onClick={() => void refresh()}>
            刷新
          </Button>
        </div>
      </PageToolbar>

      <DataSurface label="数据源列表">
        <Table<DataSource>
          size="small"
          rowKey="id"
          columns={columns}
          dataSource={paged}
          loading={loading}
          scroll={{ x: 1740 }}
          pagination={{
            current: page,
            pageSize,
            total: filtered.length,
            showSizeChanger: false,
            showTotal: (total) => `共 ${total} 条`,
            onChange: (nextPage) => {
              setPage(nextPage)
            }
          }}
          locale={{ emptyText: canManage ? '暂无数据源，点击右上角「新增数据源」开始。' : '暂无可查看的数据源。' }}
        />
      </DataSurface>

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

      <DataSourceBrowser
        open={browserOpen}
        dataSource={browserRecord}
        onClose={() => {
          setBrowserOpen(false)
          setBrowserRecord(null)
        }}
      />

      <Modal
        title={`SQL 查询 · ${queryRecord?.name ?? ''}`}
        open={queryOpen}
        onCancel={() => setQueryOpen(false)}
        footer={null}
        width={820}
      >
        <Space direction="vertical" className="full-width-stack" size={12}>
          <Input.TextArea
            className="code-input"
            value={querySql}
            onChange={(event) => setQuerySql(event.target.value)}
            placeholder="输入 SELECT / SHOW / EXPLAIN 语句"
            autoSize={{ minRows: 3, maxRows: 8 }}
          />
          <Button type="primary" loading={queryLoading} onClick={() => void runQuery()}>
            执行
          </Button>
          {queryResult && (
            <Space direction="vertical" className="full-width-stack" size={8}>
              {queryResult.truncated && (
                <Alert type="info" showIcon message={`结果已限制为前 ${queryResult.rowCount} 行`} />
              )}
              <Table
                size="small"
                scroll={{ x: 'max-content' }}
                pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 行` }}
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
            </Space>
          )}
        </Space>
      </Modal>
    </>
  )
}
