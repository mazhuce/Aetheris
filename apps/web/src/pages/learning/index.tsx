import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Col, Empty, Progress, Row, Segmented, Skeleton, Space, Tag } from 'antd'
import {
  CheckCircleOutlined,
  PlayCircleOutlined,
  ReloadOutlined,
  RightOutlined,
  SafetyCertificateOutlined,
  TrophyOutlined
} from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatCard from '@/components/StatCard'
import StatusBadge from '@/components/StatusBadge'
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
import { formatDateTime } from '@/utils/format'

const statusMeta: Record<string, { label: string; tone: 'success' | 'warning' | 'neutral' }> = {
  passed: { label: '已通过', tone: 'success' },
  in_progress: { label: '进行中', tone: 'warning' },
  not_started: { label: '未开始', tone: 'neutral' }
}

export default function LearningCenterPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [paths, setPaths] = useState<LearningPathSummary[]>([])
  const [activePathCode, setActivePathCode] = useState('')
  const [detail, setDetail] = useState<LearningPathDetail | null>(null)
  const [progress, setProgress] = useState<ProgressView | null>(null)
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [loading, setLoading] = useState(true)
  const [detailLoading, setDetailLoading] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [list, progressData, projectList] = await Promise.all([
        listLearningPaths(),
        getProgress(),
        listProjects()
      ])
      setPaths(list)
      setProgress(progressData)
      setProjects(projectList)
      // 首次进入或当前路径已下线时，回落到第一条路径。
      setActivePathCode((current) =>
        current && list.some((item) => item.code === current) ? current : list[0]?.code ?? ''
      )
    } catch {
      // 固定 key：开发模式下 StrictMode 会把 effect 跑两遍，
      // 不加 key 就会在顶部叠两条一模一样的红条。
      message.error({ content: '学习数据加载失败，请确认 learning-service 已启动', key: 'learning-load-error' })
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const activePath = paths.find((item) => item.code === activePathCode) ?? paths[0]
  const activeCode = activePath?.code ?? ''
  const skills = progress?.skills ?? []
  const modules = detail?.modules ?? []

  // 路径详情跟随选中的路径；StrictMode 下用固定 key 防重复提示。
  useEffect(() => {
    if (!activeCode) return
    let active = true
    setDetailLoading(true)
    getLearningPath(activeCode)
      .then((data) => { if (active) setDetail(data) })
      .catch(() => {
        if (active) message.error({ content: '路径详情加载失败', key: 'learning-path-detail-error' })
      })
      .finally(() => { if (active) setDetailLoading(false) })
    return () => { active = false }
  }, [activeCode, message])

  /** 继续学习：跳到第一个还没通过的任务，全部通过时回到第一个任务复习。 */
  const nextLab = useMemo(() => {
    const labs = modules.flatMap((module) => module.labs)
    return labs.find((lab) => lab.status !== 'passed') ?? labs[0]
  }, [modules])

  return (
    <>
      <PageHeader
        description="以企业真实任务为主线：读需求、看元数据、写 SQL、跑真实执行、拿可追溯评分。左侧是课程路径，右侧是你的能力证据。"
        extra={(
          <Space size={8}>
            <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>
            {nextLab && (
              <Button
                type="primary"
                icon={<PlayCircleOutlined />}
                onClick={() => navigate(`/learning/labs/${nextLab.code}`)}
              >
                继续学习
              </Button>
            )}
          </Space>
        )}
      />

      {/* 多条学习路径时提供切换；单条路径不占版面。 */}
      {paths.length > 1 && (
        <Segmented
          className="learn-path-switch"
          value={activeCode}
          onChange={(value) => setActivePathCode(value)}
          options={paths.map((item) => ({ value: item.code, label: `${item.title} · ${item.level}` }))}
        />
      )}

      <Row gutter={12} className="stat-row">
        <Col span={8}>
          <StatCard
            title="已完成任务"
            value={`${progress?.completed ?? 0} / ${progress?.total ?? 0}`}
            icon={<CheckCircleOutlined />}
          />
        </Col>
        <Col span={8}>
          <StatCard
            title="平均得分"
            value={`${progress?.averageScore ?? 0} 分`}
            icon={<TrophyOutlined />}
            tone="green"
          />
        </Col>
        <Col span={8}>
          <StatCard
            title="已获凭证"
            value={`${progress?.credentials.length ?? 0} 项`}
            icon={<SafetyCertificateOutlined />}
            tone="orange"
          />
        </Col>
      </Row>

      {/* align="top"：DataSurface 自带 height:100%，左右并排时不加限制会被拉到
          与最高一栏等高，右侧会出现大片空白边框。 */}
      <Row gutter={16} align="top">
        <Col span={16}>
          <DataSurface
            title={activePath?.title ?? '学习路径'}
            description={activePath?.description}
            extra={activePath ? <Tag>{activePath.level}</Tag> : null}
            label="学习路径详情"
          >
            {(loading || detailLoading) && !detail ? (
              <Skeleton active />
            ) : !activePath ? (
              <Empty description="还没有已发布的学习路径" />
            ) : (
              <>
                <div className="learn-meta">
                  <div className="learn-meta-item">
                    <span>目标岗位</span>
                    <strong>{activePath.targetRole || '—'}</strong>
                  </div>
                  <div className="learn-meta-item">
                    <span>预计投入</span>
                    <strong>{activePath.estimatedHours} 小时</strong>
                  </div>
                  <div className="learn-meta-item">
                    <span>任务数</span>
                    <strong>{activePath.labCount}</strong>
                  </div>
                  <div className="learn-meta-progress">
                    <span>总进度</span>
                    <Progress percent={activePath.progressPercent} showInfo={false} size="small" />
                    <strong>{activePath.progressPercent}%</strong>
                  </div>
                </div>

                <div className="learn-modules">
                  {modules.map((module) => (
                    <section className="learn-module" key={module.code} aria-label={module.title}>
                      <header className="learn-module-head">
                        <h3>{module.title}</h3>
                        <Tag>{module.level}</Tag>
                        <span className="learn-module-count">{module.labs.length} 个任务</span>
                        {module.relatedFeatures?.length ? (
                          <span className="learn-module-features">
                            对应系统功能：{module.relatedFeatures.join(' · ')}
                          </span>
                        ) : null}
                      </header>
                      {module.objective && <p className="learn-module-objective">{module.objective}</p>}
                      <div className="learn-lab-list">
                        {module.labs.map((lab) => {
                          const meta = statusMeta[lab.status] ?? statusMeta.not_started
                          return (
                            <button
                              key={lab.code}
                              type="button"
                              className="learn-lab"
                              onClick={() => navigate(`/learning/labs/${lab.code}`)}
                            >
                              <span className="learn-lab-main">
                                <span className="learn-lab-title">
                                  <strong>{lab.title}</strong>
                                  <Tag>{lab.level}</Tag>
                                  <Tag>{lab.difficulty}</Tag>
                                </span>
                                {lab.summary && <span className="learn-lab-summary">{lab.summary}</span>}
                              </span>
                              <span className="learn-lab-meta">
                                <span>{lab.estimatedMinutes} 分钟</span>
                                {lab.bestScore !== undefined && (
                                  <span className="learn-lab-score">{lab.bestScore} 分</span>
                                )}
                                <StatusBadge tone={meta.tone} dot={false}>{meta.label}</StatusBadge>
                                <RightOutlined className="learn-lab-arrow" />
                              </span>
                            </button>
                          )
                        })}
                      </div>
                    </section>
                  ))}
                </div>
              </>
            )}
          </DataSurface>
        </Col>

        <Col span={8}>
          <DataSurface title="能力雷达" description="基于真实提交" label="能力雷达">
            {skills.length === 0 ? (
              <Empty description="完成第一个任务后生成能力画像" />
            ) : (
              <ul className="learn-skills">
                {skills.map((skill) => {
                  const percent = skill.labs === 0 ? 0 : Math.round((skill.passedLabs / skill.labs) * 100)
                  return (
                    <li className="learn-skill" key={skill.code}>
                      <div className="learn-skill-head">
                        <strong>{skill.title}</strong>
                        <span className="learn-skill-score">{skill.bestScore} 分</span>
                      </div>
                      <Progress percent={percent} showInfo={false} size="small" />
                      <span className="learn-skill-meta">{skill.passedLabs}/{skill.labs} 个任务通过</span>
                    </li>
                  )
                })}
              </ul>
            )}
          </DataSurface>
        </Col>
      </Row>

      <DataSurface title="我的凭证" description="完成整条路径后自动发放结业凭证" label="我的凭证">
        {progress?.credentials.length ? (
          <ul className="learn-credentials">
            {progress.credentials.map((credential) => (
              <li className="learn-credential" key={credential.id}>
                <CheckCircleOutlined className="learn-credential-icon" />
                <span className="learn-credential-copy">
                  <strong>{credential.title}</strong>
                  <span>{formatDateTime(credential.issuedAt)}</span>
                </span>
              </li>
            ))}
          </ul>
        ) : (
          <p className="learn-empty-copy">还没有凭证。完成整条路径后会在这里发放。</p>
        )}
      </DataSurface>

      <DataSurface title="端到端项目" description="把多个任务串成可交付成果" label="端到端项目">
        {projects.length === 0 ? (
          <Empty description="暂无项目" />
        ) : (
          <Row gutter={[12, 12]}>
            {projects.map((project) => {
              const percent = project.labCount === 0 ? 0 : Math.round((project.passedLabs / project.labCount) * 100)
              return (
                <Col span={8} key={project.code}>
                  {/* 用 div + role 而不是 button：进度条在 antd 里渲染成 div，
                      button 只允许行内内容，套 div 属于无效 HTML。 */}
                  <div
                    className="learn-project"
                    role="button"
                    tabIndex={0}
                    onClick={() => navigate(`/learning/projects/${project.code}`)}
                    onKeyDown={(event) => {
                      if (event.key !== 'Enter' && event.key !== ' ') return
                      event.preventDefault()
                      navigate(`/learning/projects/${project.code}`)
                    }}
                  >
                    <span className="learn-project-head">
                      <strong>{project.title}</strong>
                      <Tag>{project.level}</Tag>
                    </span>
                    {project.summary && <span className="learn-project-summary">{project.summary}</span>}
                    <Progress percent={percent} showInfo={false} size="small" />
                    <span className="learn-project-meta">
                      {project.passedLabs}/{project.labCount} 个任务通过 · 交付物 {project.deliverables.length} 项
                    </span>
                  </div>
                </Col>
              )
            })}
          </Row>
        )}
      </DataSurface>
    </>
  )
}
