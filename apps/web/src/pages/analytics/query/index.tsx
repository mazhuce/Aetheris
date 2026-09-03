import { useEffect, useMemo, useState } from 'react'
import { App, Button, Input, Select, Space, Table, Tag, Tooltip, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CaretRightOutlined, ClearOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import { listDataSources, runAdhocQuery, type AdhocQueryResult, type DataSource } from '@/api/metadata'

const MAX_SQL_LENGTH = 4000

export default function AdhocQueryPage() {
  const { message } = App.useApp()
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [datasourceId, setDatasourceId] = useState<number>()
  const [sql, setSql] = useState('')
  const [result, setResult] = useState<AdhocQueryResult>()
  const [error, setError] = useState<string>()
  const [running, setRunning] = useState(false)

  useEffect(() => {
    void listDataSources().then(setDataSources).catch(() => message.error('数据源列表加载失败'))
  }, [message])

  const columns: TableColumnsType<Record<string, unknown>> = useMemo(() => {
    if (!result?.columns) return []
    return result.columns.map((name) => ({
      title: name,
      dataIndex: name,
      key: name,
      ellipsis: true,
      width: 160,
      render: (value: unknown) => renderCell(value)
    }))
  }, [result])

  // rows 为 unknown[][]：按索引映射为 Record，配合上面的 dataIndex。
  const dataSource = useMemo(() => {
    if (!result?.rows || !result.columns) return []
    return result.rows.map((row) => {
      const record: Record<string, unknown> = {}
      result.columns.forEach((name, index) => {
        record[name] = row[index]
      })
      return record
    })
  }, [result])

  const handleRun = async () => {
    if (!datasourceId) {
      message.warning('请先选择数据源')
      return
    }
    if (!sql.trim()) {
      message.warning('请输入 SQL 语句')
      return
    }
    setRunning(true)
    setError(undefined)
    try {
      setResult(await runAdhocQuery(datasourceId, sql.trim()))
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      setError(detail || '查询执行失败')
      setResult(undefined)
    } finally {
      setRunning(false)
    }
  }

  const disabled = running || !datasourceId || !sql.trim() || sql.length > MAX_SQL_LENGTH

  return (
    <div className="adhoc-query-page">
      <PageHeader
        description="面向分析人员的自助式只读查询：在选定数据源上执行 SELECT / SHOW / DESCRIBE / EXPLAIN，最多返回 100 行。"
        extra={
          <Space>
            <Button icon={<ClearOutlined />} onClick={() => { setSql(''); setResult(undefined); setError(undefined) }}>
              清空
            </Button>
            <Button type="primary" icon={<CaretRightOutlined />} loading={running} disabled={disabled} onClick={() => void handleRun()}>
              执行查询
            </Button>
          </Space>
        }
      />

      <DataSurface title="查询编辑器" label="SQL 输入">
        <Space direction="vertical" size={12} style={{ width: '100%' }}>
          <Space wrap>
            <span style={{ color: 'var(--text-tertiary)' }}>数据源</span>
            <Select
              showSearch
              optionFilterProp="label"
              placeholder="选择数据源"
              style={{ minWidth: 280 }}
              value={datasourceId}
              onChange={setDatasourceId}
              options={dataSources.map((source) => ({
                value: source.id,
                label: `${source.name} · ${source.type}`
              }))}
            />
            {datasourceId && (
              <Tooltip title="只读守卫拒绝一切写入语句，请勿尝试 INSERT/UPDATE/DDL">
                <Tag color="green">只读</Tag>
              </Tooltip>
            )}
          </Space>
          <Input.TextArea
            rows={8}
            value={sql}
            onChange={(event) => setSql(event.target.value)}
            placeholder={'-- 只读查询，例如：\nSELECT * FROM ods_orders LIMIT 100'}
            style={{ fontFamily: 'ui-monospace, Consolas, monospace' }}
            maxLength={MAX_SQL_LENGTH}
            showCount
          />
        </Space>
      </DataSurface>

      {error && (
        <DataSurface title="执行错误" label="错误信息">
          <Typography.Text type="danger">{error}</Typography.Text>
        </DataSurface>
      )}

      {result && (
        <DataSurface
          title={`查询结果（${result.rowCount} 行${result.truncated ? '，已截断' : ''}${result.durationMs !== undefined ? `，耗时 ${result.durationMs} ms` : ''}）`}
          label="查询结果"
        >
          <Table<Record<string, unknown>>
            rowKey={(_, index) => String(index)}
            columns={columns}
            dataSource={dataSource}
            scroll={{ x: 'max-content' }}
            pagination={{ pageSize: 20, showSizeChanger: true, showTotal: (total) => `共 ${total} 行` }}
            locale={{ emptyText: '查询没有返回数据' }}
          />
        </DataSurface>
      )}
    </div>
  )
}

function renderCell(value: unknown) {
  if (value === null || value === undefined) return <Typography.Text type="secondary">NULL</Typography.Text>
  return <Typography.Text style={{ fontSize: 13 }}>{String(value)}</Typography.Text>
}
