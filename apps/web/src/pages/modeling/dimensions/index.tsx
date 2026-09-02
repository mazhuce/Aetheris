import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Empty, Row, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { ApartmentOutlined, DatabaseOutlined, ReloadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listTables, type MetadataTable } from '@/api/metadata'
import { formatDateTime } from '@/utils/format'

/** 维度管理（A 档·只读视角）：DIM 层维度表清单与公共维度盘点。
 *  维度建模的编辑能力属于 B 档逻辑模型，这里先交付「有什么维度、被多少表引用」的盘点视图。 */
export default function DimensionPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [tables, setTables] = useState<MetadataTable[]>([])
  const [loading, setLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setTables(await listTables())
    } catch {
      message.error('维度数据加载失败')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  /** 维度表 = dim 层，或表名以 dim_ 开头。 */
  const dimensionTables = useMemo(
    () => tables.filter(
      (table) => table.layer === 'dim' || table.tableName.toLowerCase().startsWith('dim_')
    ),
    [tables]
  )

  /** 引用关系近似：其他表名中包含维度表的核心词（去 dim_ 前缀后的主干）即视为引用。 */
  const dimensionStats = useMemo(() => {
    return dimensionTables.map((dimension) => {
      const stem = dimension.tableName.toLowerCase().replace(/^dim_/, '')
      const referencing = tables.filter(
        (table) => table.id !== dimension.id && table.tableName.toLowerCase().includes(stem)
      )
      return { dimension, referencing }
    })
  }, [tables, dimensionTables])

  const columns: TableColumnsType<{ dimension: MetadataTable; referencing: MetadataTable[] }> = [
    {
      title: '维度表',
      dataIndex: ['dimension', 'tableName'],
      key: 'tableName',
      render: (value: string, record) => (
        <div>
          <Typography.Text strong style={{ fontSize: 13 }}>{value}</Typography.Text>
          {record.dimension.tableComment && (
            <div style={{ fontSize: 12, color: 'var(--text-tertiary)' }}>{record.dimension.tableComment}</div>
          )}
        </div>
      )
    },
    {
      title: '数据源',
      dataIndex: ['dimension', 'datasourceName'],
      key: 'datasourceName',
      width: 130,
      render: (value: string) => <Typography.Text type="secondary">{value}</Typography.Text>
    },
    {
      title: '字段数',
      key: 'columnCount',
      width: 80,
      render: (_, record) => record.dimension.columnCount ?? '—'
    },
    {
      title: '行数',
      key: 'rowCount',
      width: 110,
      render: (_, record) => {
        const value = record.dimension.rowCount
        return value === undefined || value === null ? '—' : value.toLocaleString()
      }
    },
    {
      title: '被引用',
      key: 'refCount',
      width: 90,
      sorter: (a, b) => a.referencing.length - b.referencing.length,
      render: (_, record) => (
        <Tag color={record.referencing.length > 0 ? 'blue' : 'default'}>{record.referencing.length} 张表</Tag>
      )
    },
    {
      title: '引用明细',
      key: 'references',
      ellipsis: true,
      render: (_, record) => record.referencing.length
        ? record.referencing.slice(0, 4).map((table) => table.tableName).join('、') + (record.referencing.length > 4 ? ` 等 ${record.referencing.length} 张` : '')
        : <Typography.Text type="secondary">暂无表名匹配引用</Typography.Text>
    },
    {
      title: '同步时间',
      key: 'syncedAt',
      width: 160,
      render: (_, record) => formatDateTime(record.dimension.syncedAt)
    },
    {
      title: '操作',
      key: 'actions',
      width: 90,
      render: () => <Button size="small" type="link" onClick={() => navigate('/assets/tables')}>表详情</Button>
    }
  ]

  return (
    <div className="dimension-page">
      <PageHeader
        description="公共维度盘点：DIM 层维度表清单及其引用情况，支撑「一处维护、多处复用」的维度治理口径。维度建模的编辑能力将随逻辑模型（规划中）交付。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <StatCard title="维度表" value={String(dimensionTables.length)} icon={<ApartmentOutlined />} footer="DIM 层或 dim_ 前缀" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="被引用维度"
            value={String(dimensionStats.filter((item) => item.referencing.length > 0).length)}
            icon={<DatabaseOutlined />}
            footer="至少被一张表引用"
            tone="green"
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="孤立维度"
            value={String(dimensionStats.filter((item) => item.referencing.length === 0).length)}
            icon={<ApartmentOutlined />}
            footer="无表名匹配引用"
            tone="orange"
          />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard
            title="总表规模"
            value={String(tables.length)}
            icon={<DatabaseOutlined />}
            footer="平台纳管全部表"
          />
        </Col>
      </Row>

      <DataSurface title="维度清单" description="按引用数排序体现复用价值" label="维度表明细">
        {dimensionTables.length ? (
          <Table
            rowKey={(record) => record.dimension.id}
            size="middle"
            columns={columns}
            dataSource={dimensionStats.sort((a, b) => b.referencing.length - a.referencing.length)}
            loading={loading}
            scroll={{ x: 1200 }}
            pagination={{ pageSize: 15, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          />
        ) : (
          <Empty description="暂无维度表。同步含 DIM 层表的数据源，或将表分层维护为维度层后生效。" />
        )}
      </DataSurface>
    </div>
  )
}
