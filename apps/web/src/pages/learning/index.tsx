import { useCallback, useEffect, useState } from 'react'
import { App, Button, Card, Col, Collapse, Empty, Progress, Row, Skeleton, Space, Statistic, Tag, Typography } from 'antd'
import { CheckCircleOutlined, ExperimentOutlined, ReloadOutlined, RightOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import PageHeader from '@/components/PageHeader'
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

const statusMeta: Record<string, { label: string; tone: 'success' | 'warning' | 'neutral' }> = {
  passed: { label: '已通过', tone: 'success' },
  in_progress: { label: '进行中', tone: 'warning' },
  not_started: { label: '未开始', tone: 'neutral' }
}

export default function LearningCenterPage() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const [paths, setPaths] = useState<LearningPathSummary[]>([])
  const [detail, setDetail] = useState<LearningPathDetail | null>(null)
  const [progress, setProgress] = useState<ProgressView | null>(null)
  const [projects, setProjects] = useState<ProjectView[]>([])
  const [loading, setLoading] = useState(true)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const list = await listLearningPaths()
      setPaths(list)
      if (list.length > 0) {
        setDetail(await getLearningPath(list[0].code))
      }
      setProgress(await getProgress())
      setProjects(await listProjects())
    } catch {
      message.error('学习数据加载失败，请确认 learning-service 已启动')
    } finally {
      setLoading(false)
    }
  }, [message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const activePath = paths[0]
  const skills = progress?.skills ?? []

  return (
    <div className="workflow-list-page">
      <PageHeader
        description="以企业真实任务为主线：读需求、看元数据、写 SQL、跑真实执行、拿可追溯评分。左侧是课程路径，右侧是你的能力证据。"
        extra={<Button icon={<ReloadOutlined />} loading={loading} onClick={() => void refresh()}>刷新</Button>}
      />

      <Row gutter={16} className="form-section-space">
        <Col span={8}>
          <Card>
            <Statistic title="已完成任务" value={progress?.completed ?? 0} suffix={`/ ${progress?.total ?? 0}`} />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="平均得分" value={progress?.averageScore ?? 0} suffix="分" />
          </Card>
        </Col>
        <Col span={8}>
          <Card>
            <Statistic title="已获凭证" value={progress?.credentials.length ?? 0} suffix="项" />
          </Card>
        </Col>
      </Row>

      <Row gutter={16}>
        <Col span={16}>
          <Card
            title={activePath ? activePath.title : '学习路径'}
            extra={activePath ? <Tag color="blue">{activePath.level}</Tag> : null}
          >
            {loading && !detail ? (
              <Skeleton active />
            ) : !activePath ? (
              <Empty description="还没有已发布的学习路径" />
            ) : (
              <>
                <Typography.Paragraph type="secondary">{activePath.description}</Typography.Paragraph>
                <Space size={24} style={{ marginBottom: 16 }}>
                  <span>目标岗位：{activePath.targetRole || '—'}</span>
                  <span>预计投入：{activePath.estimatedHours} 小时</span>
                  <span>任务数：{activePath.labCount}</span>
                </Space>
                <Progress percent={activePath.progressPercent} status="active" />
                <Collapse
                  style={{ marginTop: 16 }}
                  defaultActiveKey={detail?.modules.map((module) => module.code)}
                  items={(detail?.modules ?? []).map((module) => ({
                    key: module.code,
                    label: (
                      <Space>
                        <Tag>{module.level}</Tag>
                        <strong>{module.title}</strong>
                        <Typography.Text type="secondary">{module.labs.length} 个任务</Typography.Text>
                      </Space>
                    ),
                    children: (
                      <Space direction="vertical" style={{ width: '100%' }} size={10}>
                        {module.objective && <Typography.Text type="secondary">{module.objective}</Typography.Text>}
                        {module.labs.map((lab) => {
                          const meta = statusMeta[lab.status] ?? statusMeta.not_started
                          return (
                            <Card key={lab.code} size="small" hoverable onClick={() => navigate(`/learning/labs/${lab.code}`)}>
                              <Row align="middle" gutter={12}>
                                <Col flex="auto">
                                  <Space direction="vertical" size={2}>
                                    <Space>
                                      <ExperimentOutlined />
                                      <strong>{lab.title}</strong>
                                      <Tag>{lab.level}</Tag>
                                      <Tag>{lab.difficulty}</Tag>
                                    </Space>
                                    <Typography.Text type="secondary">{lab.summary}</Typography.Text>
                                  </Space>
                                </Col>
                                <Col>
                                  <Space>
                                    <span style={{ color: 'var(--text-tertiary)' }}>{lab.estimatedMinutes} 分钟</span>
                                    {lab.bestScore !== undefined && <Tag color="blue">{lab.bestScore} 分</Tag>}
                                    <StatusBadge tone={meta.tone} dot={false}>{meta.label}</StatusBadge>
                                    <RightOutlined />
                                  </Space>
                                </Col>
                              </Row>
                            </Card>
                          )
                        })}
                      </Space>
                    )
                  }))}
                />
              </>
            )}
          </Card>
        </Col>

        <Col span={8}>
          <Card title="能力雷达" extra={<Typography.Text type="secondary">基于真实提交</Typography.Text>}>
            {skills.length === 0 ? (
              <Empty description="完成第一个任务后生成能力画像" />
            ) : (
              <Space direction="vertical" style={{ width: '100%' }} size={14}>
                {skills.map((skill) => {
                  const percent = skill.labs === 0 ? 0 : Math.round((skill.passedLabs / skill.labs) * 100)
                  return (
                    <div key={skill.code}>
                      <Space style={{ width: '100%', justifyContent: 'space-between' }}>
                        <span>{skill.title}</span>
                        <Typography.Text type="secondary">{skill.bestScore} 分</Typography.Text>
                      </Space>
                      <Progress percent={percent} size="small" showInfo={false} />
                      <Typography.Text type="secondary" style={{ fontSize: 'var(--font-table)' }}>
                        {skill.passedLabs}/{skill.labs} 个任务通过
                      </Typography.Text>
                    </div>
                  )
                })}
              </Space>
            )}
          </Card>

          <Card title="我的凭证" style={{ marginTop: 16 }}>
            {progress?.credentials.length ? (
              <Space direction="vertical" style={{ width: '100%' }}>
                {progress.credentials.map((credential) => (
                  <Space key={credential.id}>
                    <CheckCircleOutlined style={{ color: 'var(--success)' }} />
                    <Space direction="vertical" size={0}>
                      <span>{credential.title}</span>
                      <Typography.Text type="secondary" style={{ fontSize: 'var(--font-table)' }}>
                        {new Date(credential.issuedAt).toLocaleString()}
                      </Typography.Text>
                    </Space>
                  </Space>
                ))}
              </Space>
            ) : (
              <Typography.Text type="secondary">完成整条路径后自动发放结业凭证。</Typography.Text>
            )}
          </Card>
        </Col>
      </Row>

      <Card title="端到端项目" style={{ marginTop: 16 }}
        extra={<Typography.Text type="secondary">把多个任务串成可交付成果</Typography.Text>}>
        {projects.length === 0 ? <Empty description="暂无项目" /> : (
          <Row gutter={16}>
            {projects.map((project) => {
              const percent = project.labCount === 0 ? 0 : Math.round((project.passedLabs / project.labCount) * 100)
              return (
                <Col span={8} key={project.code}>
                  <Card hoverable size="small" onClick={() => navigate(`/learning/projects/${project.code}`)}>
                    <Space direction="vertical" size={6} style={{ width: '100%' }}>
                      <Space>
                        <strong>{project.title}</strong>
                        <Tag>{project.level}</Tag>
                      </Space>
                      <Typography.Text type="secondary">{project.summary}</Typography.Text>
                      <Progress percent={percent} size="small" />
                      <Typography.Text type="secondary" style={{ fontSize: 'var(--font-table)' }}>
                        {project.passedLabs}/{project.labCount} 个任务通过 · 交付物 {project.deliverables.length} 项
                      </Typography.Text>
                    </Space>
                  </Card>
                </Col>
              )
            })}
          </Row>
        )}
      </Card>
    </div>
  )
}
