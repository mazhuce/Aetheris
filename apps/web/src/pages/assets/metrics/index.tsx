import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Empty, Input, Row, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ReloadOutlined, SearchOutlined, TableOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listTables, type MetadataTable } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

/** 指标资产（A 档·只读视角）：ADS 层与指标命名约定的表自动识别为指标资产。
 *  指标口径的登记与版本管理属于 B 档「指标管理」，这里交付指标资产的盘点与检索。 */
export default function MetricAssetsPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)
  const [keyword, setKeyword] = useState('')

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTables(await listTables())
    } catch {
      message.error('指标资产加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 指标资产 = ADS 层表，或指标命名约定（metric_/指标/agg_/stat_/report_ 前缀）命名的表。 */
  const metricTables = useMemo(
    () => tables.filter((table) => {
      const name = table.tableName.toLowerCase()
      return table.layer === 'ads'
        || name.startsWith('metric_')
        || name.startsWith('agg_')
        || name.startsWith('stat_')
        || name.startsWith('report_')
    }),
    [tables]
  )

  const filtered = useMemo(() => {
    const kw = keyword.trim().toLowerCase()
    if (!kw) return metricTables
    return metricTables.filter((table) =>
      table.tableName.toLowerCase().includes(kw)
      || (table.tableComment ?? '').toLowerCase().includes(kw)
      || (table.domain ?? '').toLowerCase().includes(kw)
    )
  }, [metricTables, keyword])

  const columns: TableColumnsType<MetadataTable> = [
    {
      title: '指标表',
      dataIndex: 'tableName',
      key: 'tableName',
      render: (value: string, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 13 }}>{value}</Typography.Text>
          {record.tableComment && (
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{record.tableComment}</div>
          )}
        </div>
      )
    },
    {
      title: '层级',
      dataIndex: 'layer',
      key: 'layer',
      width: 90,
      render: (value?: string) => (value === 'ads' ? <Tag color="purple">应用层</Tag> : <Tag>{value || '—'}</Tag>)
    },
    { title: '业务域', dataIndex: 'domain', key: 'domain', width: 100, render: (v?: string) => v || '—' },
    { title: '数据源', dataIndex: 'datasourceName', key: 'datasourceName', width: 130, render: (v: string) => <Typography.Text type="secondary">{v}</Typography.Text> },
    { title: '字段数', dataIndex: 'columnCount', key: 'columnCount', width: 80, render: (v?: number) => v ?? '—' },
    {
      title: '行数',
      dataIndex: 'rowCount',
      key: 'rowCount',
      width: 110,
      render: (v?: number) => (v === undefined || v === null ? '—' : v.toLocaleString())
    },
    {
      title: '同步时间',
      dataIndex: 'syncedAt',
      key: 'syncedAt',
      width: 160,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: () => (
        <>
          <Button size="small" type="link" onClick={() => navigate('/assets/tables')}>表详情</Button>
          <Button size="small" type="link" onClick={() => navigate('/analytics/query')}>去查询</Button>
        </>
      )
    }
  ]

  return (
    <div className="metric-assets-page">
      <PageHeader
        description="指标资产盘点：应用层（ADS）与指标命名约定的数据表自动沉淀为指标资产，支持检索与直接查询验证。指标口径登记与版本管理属于「指标管理」（规划中）。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <StatCard title="指标资产表" value={String(metricTables.length)} icon={<TableOutlined />} footer={`占全部表 ${tables.length ? Math.round((metricTables.length / tables.length) * 100) : 0}%`} />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="ADS 应用层表"
            value={String(metricTables.filter((table) => table.layer === 'ads').length)}
            icon={<TableOutlined />}
            footer="直接服务业务消费"
            tone="green"
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="覆盖业务域"
            value={String(new Set(metricTables.map((table) => table.domain).filter(Boolean)).size)}
            icon={<TableOutlined />}
            footer="有指标的域"
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="总行数规模"
            value={metricTables.reduce((sum, table) => sum + (table.rowCount ?? 0), 0).toLocaleString()}
            icon={<TableOutlined />}
            footer="指标表行数合计"
          />
        </Col>
      </Row>

      <div style={{ margin: '16px 0' }}>
        <Input
          allowClear
          prefix={<SearchOutlined />}
          placeholder="搜索指标表名、注释或业务域"
          value={keyword}
          onChange={(event) => setKeyword(event.target.value)}
          style={{ maxWidth: 360 }}
        />
      </div>

      <DataSurface title="指标资产清单" description={`共 ${filtered.length} 张表`} label="指标资产明细">
        {metricTables.length ? (
          <Table<MetadataTable>
            rowKey="id"
            size="middle"
            columns={columns}
            dataSource={filtered}
            loading={loading}
            scroll={{ x: 1200 }}
            pagination={{ pageSize: 15, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          />
        ) : (
          <Empty description="暂无指标资产。同步 ADS 层数据源或按指标命名约定（metric_/agg_ 前缀）建表后生效。" />
        )}
      </DataSurface>
    </div>
  )
}
