import { useCallback, useEffect, useState } from 'react'
import { App, Button, Input, Select, Table, Tabs, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CaretRightOutlined, ClearOutlined, PlusOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { listDataSources, runAdhocQuery, type AdhocQueryResult, type DataSource } from '@/api/metadata'

const MAX_SQL_LENGTH = 4000

/** 一个查询页签的完整状态：数据源 + SQL + 结果互不干扰，切换页签即恢复现场。 */
interface QueryTab {
  key: string
  name: string
  datasourceId?: number
  sql: string
  result?: AdhocQueryResult
  error?: string
  running?: boolean
}

let tabSeq = 0

function newTab(): QueryTab {
  tabSeq += 1
  return { key: `tab-${Date.now()}-${tabSeq}`, name: `查询 ${tabSeq}`, sql: '' }
}

export default function SqlDevelopmentPage() {
  const { message } = App.useApp()
  const [dataSources, setDataSources] = useState<DataSource[]>([])
  const [tabs, setTabs] = useState<QueryTab[]>([newTab()])
  const [activeKey, setActiveKey] = useState<string>()

  useEffect(() => {
    void listDataSources().then(setDataSources).catch(() => message.error('数据源列表加载失败'))
  }, [message])

  // activeKey 在 tabs 变化后校准：删除当前页签时切到最后一个
  useEffect(() => {
    if (!tabs.length) {
      const fresh = newTab()
      setTabs([fresh])
      setActiveKey(fresh.key)
    } else if (!tabs.some((tab) => tab.key === activeKey)) {
      setActiveKey(tabs[tabs.length - 1].key)
    }
  }, [tabs]) // eslint-disable-line react-hooks/exhaustive-deps

  const activeTab = tabs.find((tab) => tab.key === activeKey) ?? tabs[0]

  const patchTab = useCallback((key: string, patch: Partial<QueryTab>) => {
    setTabs((current) => current.map((tab) => (tab.key === key ? { ...tab, ...patch } : tab)))
  }, [])

  const handleRun = async (tab: QueryTab) => {
    if (!tab.datasourceId) {
      message.warning('请先选择数据源')
      return
    }
    if (!tab.sql.trim()) {
      message.warning('请输入 SQL 语句')
      return
    }
    patchTab(tab.key, { running: true, error: undefined })
    try {
      const result = await runAdhocQuery(tab.datasourceId, tab.sql.trim())
      patchTab(tab.key, { result, running: false })
    } catch (err) {
      const detail = (err as { response?: { data?: { message?: string } } })?.response?.data?.message
      patchTab(tab.key, { running: false, error: detail || '查询执行失败', result: undefined })
    }
  }

  const columns: TableColumnsType<Record<string, unknown>> = activeTab?.result?.columns
    ? activeTab.result.columns.map((name) => ({
        title: name,
        dataIndex: name,
        key: name,
        ellipsis: true,
        width: 160,
        render: (value: unknown) =>
          value === null || value === undefined
            ? <Typography.Text type="secondary">NULL</Typography.Text>
            : <Typography.Text style={{ fontSize: 13 }}>{String(value)}</Typography.Text>
      }))
    : []

  const resultRows = activeTab?.result?.rows
    ? activeTab.result.rows.map((row) => {
        const record: Record<string, unknown> = {}
        activeTab.result!.columns.forEach((name, index) => { record[name] = row[index] })
        return record
      })
    : []

  return (
    <div className="sql-development-page">
      <PageHeader
        description="面向数据开发的 SQL 工作台：多页签独立编辑与执行，页签间数据源、语句与结果互不干扰。与即席查询共用同一套只读守卫（仅 SELECT / SHOW / DESCRIBE / EXPLAIN）。"
        extra={
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => {
              const fresh = newTab()
              setTabs((current) => [...current, fresh])
              setActiveKey(fresh.key)
            }}
          >
            新建查询
          </Button>
        }
      />

      <Tabs
        type="editable-card"
        hideAdd
        activeKey={activeKey}
        onChange={setActiveKey}
        onEdit={(key, action) => {
          if (action === 'remove') {
            setTabs((current) => current.filter((tab) => tab.key !== key))
          }
        }}
        items={tabs.map((tab) => ({
          key: tab.key,
          label: tab.name,
          closable: tabs.length > 1,
          children: (
            <div className="sql-tab-pane">
              <DataSurface
                title={tab.name}
                description="选择数据源并执行只读查询，结果区可滚动查看完整记录"
                label={`SQL 编辑器 ${tab.name}`}
                extra={
                  <StatusBadge tone={tab.datasourceId ? 'success' : 'neutral'} dot={false}>
                    {tab.datasourceId ? '已连接 · 只读' : '未选择数据源'}
                  </StatusBadge>
                }
              >
                <PageToolbar
                  label={`查询操作区 ${tab.name}`}
                  actions={
                    <>
                      <Button
                        icon={<ClearOutlined />}
                        onClick={() => patchTab(tab.key, { sql: '', result: undefined, error: undefined })}
                      >
                        清空
                      </Button>
                      <Button
                        type="primary"
                        icon={<CaretRightOutlined />}
                        loading={tab.running}
                        disabled={tab.running || !tab.datasourceId || !tab.sql.trim() || tab.sql.length > MAX_SQL_LENGTH}
                        onClick={() => void handleRun(tab)}
                      >
                        执行查询
                      </Button>
                    </>
                  }
                >
                  <div className="filter-item filter-item-grow">
                    <span>数据源</span>
                    <Select
                      showSearch
                      optionFilterProp="label"
                      placeholder="选择数据源"
                      value={tab.datasourceId}
                      onChange={(value) => patchTab(tab.key, { datasourceId: value })}
                      options={dataSources.map((source) => ({
                        value: source.id,
                        label: `${source.name} · ${source.type}`
                      }))}
                      className="filter-control-full"
                    />
                  </div>
                </PageToolbar>

                <div className="sql-editor-area">
                  <Input.TextArea
                    rows={6}
                    value={tab.sql}
                    onChange={(event) => patchTab(tab.key, { sql: event.target.value })}
                    placeholder={'-- 只读查询，例如：\nSELECT * FROM dwd_orders_detail WHERE dt = \'2026-09-01\' LIMIT 100'}
                    className="code-input"
                    maxLength={MAX_SQL_LENGTH}
                    showCount
                  />
                </div>
              </DataSurface>

              {tab.error && (
                <DataSurface title="执行错误" label={`错误信息 ${tab.name}`}>
                  <Typography.Text type="danger">{tab.error}</Typography.Text>
                </DataSurface>
              )}

              {tab.result && (
                <DataSurface
                  title={`查询结果 · ${tab.result.rowCount} 行${tab.result.truncated ? '（已截断）' : ''}`}
                  description={tab.result.durationMs !== undefined ? `耗时 ${tab.result.durationMs} ms` : undefined}
                  label={`查询结果 ${tab.name}`}
                  className="sql-result-surface"
                >
                  <Table<Record<string, unknown>>
                    rowKey={(_, index) => String(index)}
                    columns={columns}
                    dataSource={resultRows}
                    scroll={{ x: 'max-content' }}
                    pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 行` }}
                    locale={{ emptyText: '查询没有返回数据' }}
                  />
                </DataSurface>
              )}
            </div>
          )
        }))}
      />
    </div>
  )
}