import { useCallback, useEffect, useState } from 'react'
import { Button, Col, Row, Space, Table, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { CheckCircleOutlined, DownloadOutlined, ProjectOutlined, ReloadOutlined, SafetyCertificateOutlined, TrophyOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import { ErrorState, LoadingState } from '@/components/FeedbackState'
import { getProgress, listProjects, type ProgressView, type ProjectView } from '@/api/learning'
import { useAuthSession } from '@/hooks/useAuthSession'
import { buildPortfolioMarkdown } from './portfolioExport'

export default function LearningPortfolioPage() {
  const session = useAuthSession()
  const [progress, setProgress] = useState<ProgressView | null>(null)
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()

  const refresh = useCallback(async (signal?: AbortSignal) => {
    setLoading(true)
    setError(undefined)
    try {
      const [nextProgress, nextProjects] = await Promise.all([getProgress(), listProjects()])
      if (signal?.aborted) return
      setProgress(nextProgress)
      setProjects(nextProjects)
    } catch {
      if (!signal?.aborted) setError('求职证据加载失败，请确认 learning-service 已启动。')
    } finally {
      if (!signal?.aborted) setLoading(false)
    }
  }, [])

  useEffect(() => {
    const controller = new AbortController()
    void refresh(controller.signal)
    return () => controller.abort()
  }, [refresh])

  const exportMarkdown = () => {
    if (!progress) return
    const username = session.status === 'authenticated' ? session.user.username : undefined
    const markdown = buildPortfolioMarkdown({ progress, projects, username })
    const blob = new Blob([markdown], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = `aetheris-portfolio-${new Date().toISOString().slice(0, 10)}.md`
    anchor.click()
    URL.revokeObjectURL(url)
  }

  const skillColumns: TableColumnsType<ProgressView['skills'][number]> = [
    { title: '能力', dataIndex: 'title' },
    { title: '等级', dataIndex: 'level', width: 80 },
    {
      title: '通过任务',
      key: 'labs',
      width: 110,
      render: (_, row) => `${row.passedLabs}/${row.labs}`
    },
    {
      title: '最佳得分',
      dataIndex: 'bestScore',
      width: 100,
      render: (value: number) => <Typography.Text strong style={{ fontSize: 'var(--font-body)' }}>{value}</Typography.Text>
    }
  ]

  const projectColumns: TableColumnsType<ProjectView> = [
    { title: '项目', dataIndex: 'title' },
    { title: '等级', dataIndex: 'level', width: 80 },
    {
      title: '任务进度',
      key: 'progress',
      width: 110,
      render: (_, row) => `${row.passedLabs}/${row.labCount}`
    },
    {
      title: '交付物',
      dataIndex: 'deliverables',
      render: (deliverables: string[]) => deliverables.length
        ? <Space wrap size={4}>{deliverables.map((item) => <Tag key={item}>{item}</Tag>)}</Space>
        : '—'
    }
  ]

  const credentialColumns: TableColumnsType<ProgressView['credentials'][number]> = [
    { title: '凭证', dataIndex: 'title' },
    { title: '类型', dataIndex: 'type', width: 140 },
    {
      title: '发放时间',
      dataIndex: 'issuedAt',
      width: 170,
      render: (value: string) => new Date(value).toLocaleString('zh-CN', { hour12: false })
    }
  ]

  return (
    <>
      <PageHeader
        description="汇总真实判分产生的学习进度、能力、项目交付物与凭证，可导出为求职证据包。证据不代表企业部署或第三方认证。"
        extra={(
          <Space size={8}>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>
            <Button type="primary" icon={<DownloadOutlined />} disabled={!progress} onClick={exportMarkdown}>导出证据包</Button>
          </Space>
        )}
      />

      {error ? (
        <ErrorState description={error} onRetry={() => void refresh()} />
      ) : !progress ? (
        <LoadingState rows={4} />
      ) : (
        <>
          <Row gutter={12} className="stat-row">
            <Col span={6}><StatCard title="完成进度" value={`${progress.completed}/${progress.total}`} icon={<CheckCircleOutlined />} /></Col>
            <Col span={6}><StatCard title="平均得分" value={`${progress.averageScore} 分`} icon={<TrophyOutlined />} tone="green" /></Col>
            <Col span={6}><StatCard title="已获凭证" value={`${progress.credentials.length} 项`} icon={<SafetyCertificateOutlined />} tone="orange" /></Col>
            <Col span={6}><StatCard title="项目交付" value={`${projects.reduce((sum, item) => sum + item.deliverables.length, 0)} 项`} icon={<ProjectOutlined />} /></Col>
          </Row>

          <DataSurface title="能力清单" description="由真实提交与判分累计，按能力维度汇总最佳成绩。" label="能力清单">
            <Table
              rowKey="code"
              size="small"
              columns={skillColumns}
              dataSource={progress.skills}
              pagination={false}
              locale={{ emptyText: '完成第一个任务后生成能力画像。' }}
            />
          </DataSurface>

          <DataSurface title="项目与交付物" description="端到端项目的完成度与可展示交付物。" label="项目与交付物">
            <Table
              rowKey="code"
              size="small"
              columns={projectColumns}
              dataSource={projects}
              pagination={false}
              locale={{ emptyText: '暂无项目。' }}
            />
          </DataSurface>

          <DataSurface title="凭证" description="完成整条路径后发放，可作为学习证据。" label="凭证">
            <Table
              rowKey="id"
              size="small"
              columns={credentialColumns}
              dataSource={progress.credentials}
              pagination={false}
              locale={{ emptyText: '暂无凭证。' }}
            />
          </DataSurface>
        </>
      )}
    </>
  )
}