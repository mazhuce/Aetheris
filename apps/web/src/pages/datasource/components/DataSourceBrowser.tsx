import { useEffect, useState } from 'react'
import { Alert, App, Descriptions, Drawer, Empty, Select, Space, Spin, Table, Tabs, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import {
  listDataSourceColumns,
  listDataSourceSchemas,
  listDataSourceTables,
  previewDataSourceTable,
  type DataSource,
  type DataSourceColumn,
  type DataSourceTable,
  type QueryResult
} from '@/api/metadata'
import { getDataSourceDefinition } from '../sourceCatalog'

interface DataSourceBrowserProps {
  open: boolean
  dataSource: DataSource | null
  onClose: () => void
}

export default function DataSourceBrowser({ open, dataSource, onClose }: DataSourceBrowserProps) {
  const { message } = App.useApp()
  const [schemas, setSchemas] = useState<string[]>([])
  const [schema, setSchema] = useState<string>()
  const [tables, setTables] = useState<DataSourceTable[]>([])
  const [table, setTable] = useState<string>()
  const [columns, setColumns] = useState<DataSourceColumn[]>([])
  const [preview, setPreview] = useState<QueryResult | null>(null)
  const [loadingSchemas, setLoadingSchemas] = useState(false)
  const [loadingTables, setLoadingTables] = useState(false)
  const [loadingDetails, setLoadingDetails] = useState(false)

  useEffect(() => {
    if (!open || !dataSource) return
    setSchemas([])
    setSchema(undefined)
    setTables([])
    setTable(undefined)
    setColumns([])
    setPreview(null)
    setLoadingSchemas(true)
    void listDataSourceSchemas(dataSource.id)
      .then((items) => {
        const names = items.map((item) => item.name)
        setSchemas(names)
        if (names[0]) {
          setSchema(names[0])
          void loadTables(dataSource.id, names[0])
        }
      })
      .catch(() => message.error('库 / Schema 加载失败，请先测试连接'))
      .finally(() => setLoadingSchemas(false))
  }, [open, dataSource, message])

  const loadTables = async (dataSourceId: number, nextSchema: string) => {
    setLoadingTables(true)
    setTable(undefined)
    setColumns([])
    setPreview(null)
    try {
      setTables(await listDataSourceTables(dataSourceId, nextSchema))
    } catch {
      message.error('数据表加载失败')
    } finally {
      setLoadingTables(false)
    }
  }

  const handleSchemaChange = (nextSchema: string) => {
    if (!dataSource) return
    setSchema(nextSchema)
    void loadTables(dataSource.id, nextSchema)
  }

  const handleTableChange = async (nextTable: string) => {
    if (!dataSource || !schema) return
    setTable(nextTable)
    setLoadingDetails(true)
    try {
      const nextColumns = await listDataSourceColumns(dataSource.id, schema, nextTable)
      const nextPreview = await previewDataSourceTable(dataSource.id, schema, nextTable)
      setColumns(nextColumns)
      setPreview(nextPreview)
    } catch {
      setColumns([])
      setPreview(null)
      message.error('字段或样例数据加载失败')
    } finally {
      setLoadingDetails(false)
    }
  }

  const columnDefinitions: TableColumnsType<DataSourceColumn> = [
    { title: '#', dataIndex: 'ordinal', width: 56 },
    { title: '字段名', dataIndex: 'name', width: 180 },
    { title: '类型', dataIndex: 'type', width: 160, render: (value: string) => <Tag>{value}</Tag> },
    { title: '长度', dataIndex: 'size', width: 90, render: (value?: number) => value || '—' },
    { title: '可空', dataIndex: 'nullable', width: 80, render: (value?: boolean) => value === false ? '否' : '是' },
    { title: '说明', dataIndex: 'remarks', ellipsis: true, render: (value?: string) => value || '—' }
  ]

  const previewRows = preview?.rows.map((row, rowIndex) => {
    const record: Record<string, unknown> = { key: rowIndex }
    preview.columns.forEach((column, columnIndex) => {
      record[column] = row[columnIndex]
    })
    return record
  }) ?? []

  const definition = getDataSourceDefinition(dataSource?.type)

  return (
    <Drawer
      title="库表浏览"
      open={open}
      onClose={onClose}
      width="min(100vw, 1000px)"
      destroyOnHidden
    >
      {dataSource && (
        <>
          <Descriptions size="small" column={4} className="datasource-browser-summary">
            <Descriptions.Item label="数据源">{dataSource.name}</Descriptions.Item>
            <Descriptions.Item label="类型"><Tag>{definition.label}</Tag></Descriptions.Item>
            <Descriptions.Item label="地址">{dataSource.host}:{dataSource.port ?? definition.defaultPort}</Descriptions.Item>
            <Descriptions.Item label="默认库">{dataSource.databaseName || '—'}</Descriptions.Item>
          </Descriptions>

          <div className="datasource-browser-selectors">
            <div>
              <Typography.Text type="secondary">库 / Schema</Typography.Text>
              <Select
                value={schema}
                options={schemas.map((name) => ({ value: name, label: name }))}
                loading={loadingSchemas}
                placeholder="选择库"
                onChange={handleSchemaChange}
                className="full-width-control"
              />
            </div>
            <div>
              <Typography.Text type="secondary">数据表</Typography.Text>
              <Select
                value={table}
                options={tables.map((item) => ({ value: item.name, label: item.name }))}
                loading={loadingTables}
                disabled={!schema}
                showSearch
                placeholder={tables.length ? '选择数据表' : '当前库暂无数据表'}
                onChange={(value) => void handleTableChange(value)}
                className="full-width-control"
              />
            </div>
          </div>

          {!table && !loadingSchemas && !loadingTables ? (
            <Empty description="选择一个数据表后查看字段结构与前 50 行样例数据" />
          ) : (
            <Spin spinning={loadingDetails}>
              <Tabs
                items={[
                  {
                    key: 'columns',
                    label: `字段结构 ${columns.length ? `(${columns.length})` : ''}`,
                    children: <Table rowKey={(record) => `${record.ordinal}-${record.name}`} size="small" columns={columnDefinitions} dataSource={columns} pagination={false} scroll={{ x: 720 }} />
                  },
                  {
                    key: 'preview',
                    label: `数据预览 ${preview ? `(${preview.rowCount})` : ''}`,
                    children: preview ? (
                      <Space direction="vertical" className="full-width-stack">
                        {preview.truncated && <Alert type="info" showIcon message="结果已限制为前 50 行" />}
                        <Table
                          size="small"
                          pagination={false}
                          dataSource={previewRows}
                          columns={preview.columns.map((column) => ({ title: column, dataIndex: column, key: column, ellipsis: true, width: 160 }))}
                          scroll={{ x: 'max-content', y: 430 }}
                        />
                      </Space>
                    ) : <Empty description="暂无可预览数据" />
                  }
                ]}
              />
            </Spin>
          )}
        </>
      )}
    </Drawer>
  )
}
