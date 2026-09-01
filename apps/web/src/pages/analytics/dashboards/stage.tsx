import { useEffect, useState } from 'react'
import { ArrowLeftOutlined, ExpandOutlined } from '@ant-design/icons'
import { Button, Spin, Tag, message } from 'antd'
import { useNavigate, useParams } from 'react-router-dom'
import BrandMark from '@/components/BrandMark'
import {
  getAnalyticsDashboard,
  getPublishedDashboard,
  listAnalyticsDatasets,
  type AnalyticsDashboard,
  type AnalyticsDataset,
  type DashboardSpec
} from '@/api/analytics'
import DashboardCanvas from './components/DashboardCanvas'

function DashboardStage({ mode }: { mode: 'preview' | 'published' }) {
  const { id, slug } = useParams()
  const navigate = useNavigate()
  const [dashboard, setDashboard] = useState<AnalyticsDashboard>()
  const [datasets, setDatasets] = useState<AnalyticsDataset[]>([])
  const [spec, setSpec] = useState<DashboardSpec>()

  useEffect(() => {
    const loadDashboard = mode === 'preview'
      ? getAnalyticsDashboard(Number(id))
      : getPublishedDashboard(slug || '')
    Promise.all([loadDashboard, listAnalyticsDatasets()])
      .then(([nextDashboard, nextDatasets]) => {
        setDashboard(nextDashboard)
        setDatasets(nextDatasets)
        setSpec(mode === 'preview' ? nextDashboard.draftSpec : nextDashboard.publishedSpec)
      })
      .catch(() => message.error('看板加载失败'))
  }, [id, mode, slug])

  useEffect(() => {
    if (mode !== 'preview' || !id) return
    const channel = new BroadcastChannel(`aetheris-dashboard-${id}`)
    channel.onmessage = (event) => {
      if (event.data?.type === 'spec') setSpec(event.data.spec)
    }
    return () => channel.close()
  }, [id, mode])

  if (!dashboard || !spec) return <div className="dashboard-stage-loading"><Spin /><span>正在加载数据看板…</span></div>

  return (
    <div className="dashboard-stage-page">
      <header className="dashboard-stage-header">
        <div className="dashboard-stage-brand">
          <BrandMark />
          <div><strong>{dashboard.name}</strong><span>{dashboard.description || 'Aetheris Data Dashboard'}</span></div>
        </div>
        <div className="dashboard-stage-meta">
          {mode === 'preview' ? <Tag>草稿实时预览</Tag> : <Tag>已发布 v{dashboard.publishedVersion}</Tag>}
          {mode === 'preview' && <Button type="text" icon={<ArrowLeftOutlined />} onClick={() => navigate(`/analytics/dashboards/${dashboard.id}/edit`)}>返回编辑</Button>}
          <Button type="text" icon={<ExpandOutlined />} onClick={() => document.documentElement.requestFullscreen?.()}>全屏</Button>
        </div>
      </header>
      <main className="dashboard-stage-canvas"><DashboardCanvas spec={spec} datasets={datasets} /></main>
    </div>
  )
}

export function DashboardPreviewPage() { return <DashboardStage mode="preview" /> }
export function PublishedDashboardPage() { return <DashboardStage mode="published" /> }
