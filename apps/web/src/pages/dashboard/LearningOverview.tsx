import { useEffect, useState } from 'react'
import { Button, Progress } from 'antd'
import { PlayCircleOutlined, ReadOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import {
  getLearningPath,
  getProgress,
  listLearningPaths,
  listProjects,
  type LearningPathDetail,
  type LearningPathSummary,
  type ProgressView,
  type ProjectView
} from '@/api/learning'
import DataSurface from '@/components/DataSurface'
import { LoadingState } from '@/components/FeedbackState'

const ACTIVE_PATH_STORAGE_KEY = 'aetheris.learning.activePath'

/** 学习者首页概览：复用学习中心接口，展示进度、凭证与下一步。 */
export default function LearningOverview() {
  const navigate = useNavigate()
  const [paths, setPaths] = useState<LearningPathSummary[]>([])
  const [progress, setProgress] = useState<ProgressView | null>(null)
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [detail, setDetail] = useState<LearningPathDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let cancelled = false
    const stored = localStorage.getItem(ACTIVE_PATH_STORAGE_KEY)
    void Promise.allSettled([listLearningPaths(), getProgress(), listProjects()])
      .then(async ([pathResult, progressResult, projectResult]) => {
        if (cancelled) return
        const pathList = pathResult.status === 'fulfilled' ? pathResult.value : []
        setPaths(pathList)
        if (progressResult.status === 'fulfilled') setProgress(progressResult.value)
        if (projectResult.status === 'fulfilled') setProjects(projectResult.value)
        const active = pathList.find((item) => item.code === stored) ?? pathList[0]
        if (active) {
          try {
            const pathDetail = await getLearningPath(active.code)
            if (!cancelled) setDetail(pathDetail)
          } catch {
            // 路径详情失败时保留概览，仅隐藏“继续学习”入口。
          }
        }
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [])

  if (!loading && !progress) return null

  const activeSummary = paths.find((item) => item.code === detail?.code) ?? paths[0]
  const nextLab = detail?.modules.flatMap((module) => module.labs).find((lab) => !lab.passed)
  const completedProjects = projects.filter((project) => project.labCount > 0 && project.passedLabs >= project.labCount).length
  const metrics = [
    { label: '已完成任务', value: progress ? `${progress.completed}/${progress.total}` : '—', detail: '覆盖当前全部路径' },
    { label: '平均得分', value: progress ? `${progress.averageScore}` : '—', detail: '通过任务的最佳分均值' },
    { label: '路径凭证', value: progress ? `${progress.credentials.length}` : '—', detail: '完成整条路径后发放' },
    { label: '实战项目', value: projects.length ? `${completedProjects}/${projects.length}` : '—', detail: '全部任务通过后完成' }
  ]

  return (
    <DataSurface
      className="learning-overview-panel"
      title="我的学习"
      description="从当前任务到求职证据，接着上次的进度继续。"
    >
      {loading ? <LoadingState rows={2} /> : (
        <div className="learning-overview">
          <div>
            <div className="learning-overview-metrics">
              {metrics.map((metric) => (
                <div className="learning-overview-metric" key={metric.label}>
                  <span>{metric.label}</span>
                  <strong>{metric.value}</strong>
                  <small>{metric.detail}</small>
                </div>
              ))}
            </div>
            <div className="learning-overview-next">
              <div className="learning-overview-current">
                <span>当前路径</span>
                <strong>{activeSummary?.title ?? '选择一条学习路径'}</strong>
                <small>
                  {activeSummary
                    ? `${activeSummary.completedLabs}/${activeSummary.labCount} 题 · 进度 ${activeSummary.progressPercent}%`
                    : '前往学习中心开始第一题'}
                </small>
                <Progress
                  className="learning-overview-progress"
                  percent={activeSummary?.progressPercent ?? 0}
                  showInfo={false}
                  size="small"
                />
              </div>
              {nextLab ? (
                <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => navigate(`/learning/labs/${nextLab.code}`)}>
                  继续学习
                </Button>
              ) : (
                <Button icon={<ReadOutlined />} onClick={() => navigate('/learning')}>查看学习中心</Button>
              )}
            </div>
            {nextLab && <p className="learning-overview-hint">下一题：{nextLab.title}</p>}
          </div>
        </div>
      )}
    </DataSurface>
  )
}