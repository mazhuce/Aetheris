import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Card, Col, Row, Select, Spin, Table, Tag, Tooltip } from 'antd'
import type { TableColumnsType } from 'antd'
import { ArrowRightOutlined, PartitionOutlined, ReloadOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { EmptyState } from '@/components/FeedbackState'
import { useResponsivePageSize } from '@/hooks/useResponsivePageSize'
import {
  getWarehouseMap,
  listDataSources,
  type DataSource,
  type WarehouseLayerSummary,
  type WarehouseMap,
  type WarehouseTableBrief
} from '@/api/metadata'

/** 各层职责说明，鼠标悬停时展示，帮助不熟悉数仓分层的人理解。 */
const LAYER_HINTS: Record<string, string> = {
  ods: '贴源层：与源系统结构一致，保留原始脏数据，不做清洗',
  dwd: '明细层：去重、类型转换、枚举标准化后的事实明细',
  dws: '汇总层：按主题轻度聚合的宽表，粒度为「维度 + 日期」',
  ads: '应用层：面向报表与指标的最终结果表，口径已固化',
  dim: '维度层：公共维表，供各层关联使用',
  other: '未分层：库名与表名都未命中分层约定，可人工指定层级'
}

export default function WarehouseMapPage() {
  const { message } = App.useApp()
  const [data, setData] = useState<WarehouseMap | null>(null)
  const [loading, setLoading] = useState(false)
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [datasourceName, setDatasourceName] = useState<string | undefined>()
  const [selectedLayer, setSelectedLayer] = useState<string | undefined>()
  const pageSize = useResponsivePageSize(8, 6, 5, 3)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      setData(await getWarehouseMap(datasourceName))
    } catch {
      message.error('数仓地图加载失败')
    } finally {
      setLoading(false)
    }
  }, [datasourceName, message])

  useEffect(() => { void refresh() }, [refresh])

  useEffect(() => {
    void (async () => {
      try {
        setDataSources(await listDataSources())
      } catch {
        /* 数据源列表只用于筛选，失败不影响地图展示 */
      }
    })()
  }, [])

  // 只展示有表的层级；全为空时返回空数组，由 Empty 占位。
  const activeLayers = useMemo(
    () => (data?.layers ?? []).filter((l) => l.tableCount > 0),
    [data]
  )

  const selected = useMemo(
    () => activeLayers.find((l) => l.layer === selectedLayer) ?? null,
    [activeLayers, selectedLayer]
  )

  const columns: TableColumnsType<WarehouseTableBrief> = [
    { title: '库', dataIndex: 'schemaName', key: 'schemaName', width: 120, render: (v?: string) => v || '—' },
    { title: '表名', dataIndex: 'tableName', key: 'tableName', width: 200 },
    { title: '说明', dataIndex: 'tableComment', key: 'tableComment', render: (v?: string) => v || '—' },
    { title: '字段数', dataIndex: 'columnCount', key: 'columnCount', width: 90, render: (v?: number) => v ?? '—' },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (v?: string) =>
        v === 'stale' ? <StatusBadge tone="warning">已消失</StatusBadge> : <StatusBadge tone="success">正常</StatusBadge>
    }
  ]

  return (
    <>
      <PageHeader
        description="按 ODS → DWD → DWS → ADS 分层结构展示数据流向，选择层级查看对应表清单。层级由采集时按库名推断，可在元数据目录中人工修正。"
      />

      <PageToolbar actions={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}>
        <div className="filter-bar">
          <div className="filter-item">
            <span>数据源</span>
            <Select
              placeholder="全部数据源"
              value={datasourceName}
              onChange={(v) => { setDatasourceName(v); setSelectedLayer(undefined) }}
              allowClear
              className="filter-control-wide"
              options={dataSources.map((d) => ({ value: d.name, label: d.name }))}
            />
          </div>
        </div>
      </PageToolbar>

      <Spin spinning={loading}>
        {activeLayers.length === 0 ? (
          <EmptyState title="暂无分层元数据" description="请先在元数据管理中执行采集，平台会在采集时按库名自动推断层级。" />
        ) : (
          <>
            <Row gutter={[12, 12]} className="warehouse-flow">
              {activeLayers.map((layer, index) => (
                <Col key={layer.layer} flex="1 1 0" className="warehouse-layer-column">
                  <LayerCard
                    layer={layer}
                    active={selectedLayer === layer.layer}
                    showArrow={index < activeLayers.length - 1}
                    onClick={() =>
                      setSelectedLayer(selectedLayer === layer.layer ? undefined : layer.layer)
                    }
                  />
                </Col>
              ))}
            </Row>

            <DataSurface
              title={
                selected
                  ? `${selected.label}（${selected.layer}）· ${selected.tableCount} 张表`
                  : `全部表 · ${data?.total ?? 0} 张`
              }
              extra={
                selected && (
                  <Button type="link" size="small" onClick={() => setSelectedLayer(undefined)}>
                    查看全部
                  </Button>
                )
              }
            >
              <Table<WarehouseTableBrief>
                size="small"
                rowKey="id"
                columns={columns}
                dataSource={selected ? selected.tables : activeLayers.flatMap((l) => l.tables)}
                pagination={{ pageSize, showTotal: (t) => `共 ${t} 条` }}
                locale={{ emptyText: '该层暂无表' }}
              />
            </DataSurface>
          </>
        )}
      </Spin>
    </>
  )
}

interface LayerCardProps {
  layer: WarehouseLayerSummary
  active: boolean
  showArrow: boolean
  onClick: () => void
}

function LayerCard({ layer, active, showArrow, onClick }: LayerCardProps) {
  return (
    <div className="warehouse-layer-step">
      <Tooltip title={LAYER_HINTS[layer.layer]}>
        <Card
          size="small"
          hoverable
          className={`warehouse-layer-card${active ? ' is-active' : ''}`}
          role="button"
          tabIndex={0}
          aria-pressed={active}
          onClick={onClick}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') {
              event.preventDefault()
              onClick()
            }
          }}
        >
          <div className="warehouse-layer-heading">
            <Tag>{layer.label}</Tag>
            <PartitionOutlined className="warehouse-layer-icon" />
          </div>
          <div className="warehouse-layer-count">
            {layer.tableCount}
          </div>
          <div className="warehouse-layer-unit">张表</div>
          <div
            className="warehouse-layer-schemas"
            title={layer.schemas.join('、')}
          >
            {layer.schemas.length > 0 ? layer.schemas.join('、') : '—'}
          </div>
        </Card>
      </Tooltip>
      {showArrow && <ArrowRightOutlined className="warehouse-flow-arrow" />}
    </div>
  )
}
