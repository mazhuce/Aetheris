import { useEffect, useState } from 'react'
import { ArrowLeftOutlined, ExpandOutlined } from '@ant-design/icons'
import { Button, Spin, Tag } from 'antd'
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
import { DashboardEmptyState, DashboardLoadError } from './DashboardRequestState'
import { dashboardErrorMessage } from './dashboardErrors'

function DashboardStage({ mode }: { mode: 'preview' | 'published' }) {
  const { id, slug } = useParams()
  const navigate = useNavigate()
  const [dashboard, setDashboard] = useState<AnalyticsDashboard>()
  const [datasets, setDatasets] = useState<AnalyticsDataset[]>([])
  const [spec, setSpec] = useState<DashboardSpec>()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string>()
  const [retryKey, setRetryKey] = useState(0)

  useEffect(() => {
    const controller = new AbortController()
    let active = true
    setLoading(true)
    setError(undefined)
    setDashboard(undefined)
    setDatasets([])
    setSpec(undefined)

    const previewId = Number(id)
    if (mode === 'preview' && (!id || !Number.isSafeInteger(previewId) || previewId <= 0)) {
      setLoading(false)
      setError('看板地址无效，请从看板列表重新打开。')
      return () => { active = false; controller.abort() }
    }
    if (mode === 'published' && !slug) {
      setLoading(false)
      setError('看板发布地址无效，请检查链接。')
      return () => { active = false; controller.abort() }
    }

    const loadDashboard = mode === 'preview'
      ? getAnalyticsDashboard(previewId, controller.signal)
      : getPublishedDashboard(slug || '', controller.signal)
    Promise.all([loadDashboard, listAnalyticsDatasets(controller.signal)])
      .then(([nextDashboard, nextDatasets]) => {
        if (!active) return
        setDashboard(nextDashboard)
        setDatasets(nextDatasets.filter((dataset) => dataset.status === 'published'))
        setSpec(mode === 'preview' ? nextDashboard.draftSpec : nextDashboard.publishedSpec)
      })
      .catch((reason) => {
        if (active && !controller.signal.aborted) setError(dashboardErrorMessage(reason, '看板加载失败，请稍后重试。'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
      controller.abort()
    }
  }, [id, mode, slug, retryKey])

  useEffect(() => {
    if (mode !== 'preview' || !id) return
    const channel = new BroadcastChannel(`aetheris-dashboard-${id}`)
    let active = true
    channel.onmessage = (event) => {
      if (active && event.data?.type === 'spec') setSpec(event.data.spec)
    }
    return () => { active = false; channel.close() }
  }, [id, mode])

  if (error) return <DashboardLoadError message={error} onRetry={() => setRetryKey((value) => value + 1)} />
  if (loading || !dashboard) return <div className="dashboard-stage-loading"><Spin /><span>正在加载数据看板…</span></div>
  if (!spec) return <DashboardEmptyState message={mode === 'published' ? '该看板尚未发布可用版本。' : '该看板暂无草稿内容。'} />

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
      <main className="dashboard-stage-canvas"><DashboardCanvas spec={spec} datasets={datasets} dashboardKey={`${mode}:${dashboard.id}:${dashboard.publishedVersion}`} /></main>
    </div>
  )
}

export function DashboardPreviewPage() { return <DashboardStage mode="preview" /> }
export function PublishedDashboardPage() { return <DashboardStage mode="published" /> }
