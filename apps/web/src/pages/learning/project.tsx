import { useEffect, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import { App, Button, Col, Empty, List, Progress, Row, Skeleton, Space, Tag, Typography } from 'antd'
import { ArrowLeftOutlined, ExperimentOutlined, RightOutlined } from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import { getProject, type ProjectDetail } from '@/api/learning'

const statusMeta: Record<string, { text: string; tone: 'success' | 'warning' | 'neutral' }> = {
  passed: { text: '已通过', tone: 'success' },
  in_progress: { text: '进行中', tone: 'warning' },
  not_started: { text: '未开始', tone: 'neutral' }
}

export default function LearningProjectPage() {
  const { code = '' } = useParams()
  const navigate = useNavigate()
  const { message } = App.useApp()
  const [project, setProject] = useState<ProjectDetail | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    let active = true
    getProject(code)
      .then((data) => { if (active) setProject(data) })
      .catch(() => { if (active) message.error('项目加载失败') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [code, message])

  if (loading) return <Skeleton active paragraph={{ rows: 8 }} />
  if (!project) return <Empty description="项目不存在" />

  const percent = project.labCount === 0 ? 0 : Math.round((project.passedLabs / project.labCount) * 100)

  return (
    <>
      <PageHeader
        title={project.title}
        description={project.summary}
        extra={<Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/learning')}>返回学习中心</Button>}
        meta={<Space wrap><Tag>{project.level}</Tag><Tag>{project.labCount} 个任务</Tag>
          <StatusBadge tone={percent === 100 ? 'success' : 'warning'} dot={false}>{percent}% 完成</StatusBadge></Space>}
      />

      <Row gutter={16} align="top">
        <Col span={8}>
          <DataSurface title="项目背景" label="项目背景">
            <Typography.Paragraph type="secondary">{project.scenario}</Typography.Paragraph>
            <Typography.Text strong>交付物</Typography.Text>
            <Space direction="vertical" style={{ marginTop: 8, width: '100%' }}>
              {project.deliverables.map((item) => <Tag key={item}>{item}</Tag>)}
            </Space>
          </DataSurface>
          <DataSurface title="项目进度" label="项目进度" style={{ marginTop: 16 }}>
            <Progress percent={percent} status="active" />
            <Typography.Text type="secondary">{project.passedLabs}/{project.labCount} 个任务已通过</Typography.Text>
          </DataSurface>
        </Col>
        <Col span={16}>
          <DataSurface title="任务清单" label="任务清单">
            <List
              dataSource={project.labs}
              renderItem={(lab) => {
                const meta = statusMeta[lab.status] ?? statusMeta.not_started
                return (
                  <List.Item
                    actions={[
                      <Button key="open" type="link" onClick={() => navigate(`/learning/labs/${lab.code}`)}>
                        进入任务 <RightOutlined />
                      </Button>
                    ]}
                  >
                    <List.Item.Meta
                      avatar={<ExperimentOutlined />}
                      title={<Space>{lab.title}<Tag>{lab.level}</Tag>{lab.bestScore !== undefined && <span className="learn-lab-score">{lab.bestScore} 分</span>}
                        <StatusBadge tone={meta.tone} dot={false}>{meta.text}</StatusBadge></Space>}
                      description={<>{lab.summary} · 约 {lab.estimatedMinutes} 分钟</>}
                    />
                  </List.Item>
                )
              }}
            />
          </DataSurface>
        </Col>
      </Row>
    </>
  )
}
