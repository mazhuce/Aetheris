import { useCallback, useEffect, useState } from 'react'
import { App, Button, Col, Row, Select, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, CloudSyncOutlined, ExclamationCircleOutlined, ReloadOutlined, SyncOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import PageToolbar from '@/components/PageToolbar'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { listAllSyncRuns, listTasks, type SyncRunRecord, type IngestionTask } from '@/api/ingestion'
import { formatDateTime } from '@/utils/format'

const statusMeta: Record<SyncRunRecord['status'], { label: string; color: string }> = {
  running: { label: '同步中', color: 'processing' },
  success: { label: '成功', color: 'success' },
  failed: { label: '失败', color: 'error' }
}

export default function DataSyncPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [runs, setRuns] = useState<SyncRunRecord[]>([])
  const [tasks, setTasks] = useState<IngestionTask[]>([])
  const [loading, setLoading] = useState(false)
  const [taskId, setTaskId] = useState<number>()
  const [status, setStatus] = useState<string>()

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [runList, taskList] = await Promise.all([
        listAllSyncRuns(taskId, status),
        listTasks().catch(() => [] as IngestionTask[])
      ])
      setRuns(runList)
      setTasks(taskList)
    } catch {
      message.error('同步运行记录加载失败')
    } finally {
      setLoading(false)
    }
  }, [message, taskId, status])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const successCount = runs.filter((run) => run.status === 'success').length
  const failedCount = runs.filter((run) => run.status === 'failed').length
  const totalRows = runs.reduce((sum, run) => sum + (run.rowsWritten ?? 0), 0)

  const columns: TableColumnsType<SyncRunRecord> = [
    {
      title: '同步任务',
      dataIndex: 'taskName',
      key: 'taskName',
      width: 200,
      render: (value: string) => <Typography.Text strong style={{ fontSize: 13 }}>{value}</Typography.Text>
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      width: 90,
      render: (value: SyncRunRecord['status']) => {
        const meta = statusMeta[value]
        return <Tag color={meta.color}>{meta.label}</Tag>
      }
    },
    {
      title: '读取行数',
      dataIndex: 'rowsRead',
      key: 'rowsRead',
      width: 100,
      render: (value?: number) => (value === undefined || value === null ? '—' : value.toLocaleString())
    },
    {
      title: '写入行数',
      dataIndex: 'rowsWritten',
      key: 'rowsWritten',
      width: 100,
      render: (value?: number) => (value === undefined || value === null ? '—' : value.toLocaleString())
    },
    {
      title: '耗时',
      dataIndex: 'durationMs',
      key: 'durationMs',
      width: 100,
      render: (value?: number) => (value === undefined || value === null ? '—' : value >= 1000 ? `${(value / 1000).toFixed(1)} s` : `${value} ms`)
    },
    {
      title: '触发',
      dataIndex: 'triggeredBy',
      key: 'triggeredBy',
      width: 110,
      render: (value?: string) => {
        if (!value) return '—'
        return value === 'scheduler' ? <Tag>定时调度</Tag> : <Typography.Text code>{value}</Typography.Text>
      }
    },
    {
      title: '开始时间',
      dataIndex: 'startedAt',
      key: 'startedAt',
      width: 160,
      render: (value?: string) => formatDateTime(value)
    },
    {
      title: '错误信息',
      dataIndex: 'errorMessage',
      key: 'errorMessage',
      ellipsis: true,
      render: (value?: string) => value
        ? <Typography.Text type="danger" style={{ fontSize: 12 }}>{value}</Typography.Text>
        : '—'
    }
  ]

  return (
    <div className="data-sync-page">
      <PageHeader
        description="数据同步运行总览：跨任务的批量同步执行历史，含读写行数、耗时与失败原因。同步任务的配置与手动触发在「接入任务」页。"
        extra={<Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={[16, 16]}>
        <Col xs={12} lg={6}>
          <StatCard title="成功同步" value={String(successCount)} icon={<CheckCircleOutlined />} footer={`最近 ${runs.length} 次执行`} tone="green" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="失败同步" value={String(failedCount)} icon={<ExclamationCircleOutlined />} footer={failedCount > 0 ? '需要排查' : '运行平稳'} tone="orange" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="累计写入行数" value={totalRows.toLocaleString()} icon={<CloudSyncOutlined />} footer="最近记录合计" />
        </Col>
        <Col xs={12} lg={6}>
          <StatCard title="同步任务数" value={String(tasks.length)} icon={<SyncOutlined />} footer="已配置的接入任务" />
        </Col>
      </Row>

      <PageToolbar>
        <div className="filter-bar">
          <div className="filter-item">
            <span>任务</span>
            <Select
              placeholder="全部任务"
              allowClear
              showSearch
              optionFilterProp="label"
              value={taskId}
              onChange={setTaskId}
              className="filter-control-sm"
              style={{ minWidth: 200 }}
              options={tasks.map((task) => ({ value: task.id, label: task.name }))}
            />
          </div>
          <div className="filter-item">
            <span>状态</span>
            <Select
              placeholder="全部状态"
              allowClear
              value={status}
              onChange={setStatus}
              className="filter-control-sm"
              options={Object.entries(statusMeta).map(([value, meta]) => ({ value, label: meta.label }))}
            />
          </div>
          <Button type="primary" onClick={() => void refresh()}>查询</Button>
          <Button onClick={() => navigate('/integration/tasks')}>去配置任务</Button>
        </div>
      </PageToolbar>

      <DataSurface title="同步执行记录" description="最近 200 条" label="同步运行明细">
        <Table<SyncRunRecord>
          rowKey="id"
          columns={columns}
          dataSource={runs}
          loading={loading}
          scroll={{ x: 1200 }}
          pagination={{ pageSize: 10, showSizeChanger: false, showTotal: (total) => `共 ${total} 条` }}
          locale={{ emptyText: <Typography.Text type="secondary">暂无同步记录。在接入任务页创建并运行一个同步任务后，执行历史会出现在这里。</Typography.Text> }}
        />
      </DataSurface>
    </div>
  )
}
