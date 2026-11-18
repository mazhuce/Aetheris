import { useCallback, useEffect, useMemo, useState } from 'react'
import { useNavigate, useParams } from 'react-router-dom'
import {
  Alert, App, Button, Col, Descriptions, Divider, Drawer, Empty, Input, List, Row, Skeleton, Space,
  Steps, Table, Tag, Typography
} from 'antd'
import {
  ArrowLeftOutlined, BulbOutlined, CheckCircleFilled, CloseCircleFilled, PlayCircleOutlined,
  ReloadOutlined, RobotOutlined, SendOutlined
} from '@ant-design/icons'
import PageHeader from '@/components/PageHeader'
import DataSurface from '@/components/DataSurface'
import StatusBadge from '@/components/StatusBadge'
import {
  chatWithTutor, getLab, getTutorHistory, listSubmissions, requestHint, resetLab, startLab, submitLab,
  type HintView, type LabDetail, type SubmissionView, type TutorMessage
} from '@/api/learning'
import { formatDateTime } from '@/utils/format'
import { ErrorState } from '@/components/FeedbackState'

function labLoadError(error: unknown): string {
  const response = (error as { response?: { status?: number; data?: { message?: string } } })?.response
  const status = response?.status
  const message = response?.data?.message ?? ''
  if (status === 404 || message.includes('不存在')) return '任务不存在或已下线，请返回学习路径重新选择。'
  if (status === 403) return '没有访问此任务的权限（403），请检查当前账号。'
  if (status === 503) return '学习服务暂时不可用（503），请稍后重试。'
  return '任务加载失败，请稍后重试。'
}

export default function LearningLabPage() {
  const { code = '' } = useParams()
  const navigate = useNavigate()
  const { message, modal } = App.useApp()
  const [lab, setLab] = useState<LabDetail | null>(null)
  const [sql, setSql] = useState('')
  const [result, setResult] = useState<SubmissionView | null>(null)
  const [hint, setHint] = useState<HintView | null>(null)
  const [submissions, setSubmissions] = useState<SubmissionView[]>([])
  const [tutorOpen, setTutorOpen] = useState(false)
  const [tutorMessages, setTutorMessages] = useState<TutorMessage[]>([])
  const [tutorInput, setTutorInput] = useState('')
  const [tutorLoading, setTutorLoading] = useState(false)
  const [tutorModel, setTutorModel] = useState('')
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string>()
  const [starting, setStarting] = useState(false)
  const [submitting, setSubmitting] = useState(false)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const [detail, history] = await Promise.all([getLab(code), listSubmissions(code)])
      setLoadError(undefined)
      setLab(detail)
      setSubmissions(history)
      setSql((current) => (current.trim().length > 0 ? current : detail.starterSql ?? ''))
    } catch (error) {
      setLoadError(labLoadError(error))
    } finally {
      setLoading(false)
    }
  }, [code, message])

  useEffect(() => {
    void refresh()
  }, [refresh])

  const handleStart = async () => {
    setStarting(true)
    try {
      const sandbox = await startLab(code)
      setLab((current) => (current ? { ...current, sandboxSchema: sandbox.schemaName, sandboxActive: true } : current))
      message.success(`沙箱已就绪：${sandbox.schemaName}（${sandbox.tier === 'medium' ? 'Medium 档' : 'Light 档'}）`)
    } catch {
      message.error('沙箱创建失败，请稍后重试')
    } finally {
      setStarting(false)
    }
  }

  const handleReset = () => {
    modal.confirm({
      title: '重置实验沙箱',
      content: '会删除当前沙箱中的所有改动，并从教学数据集重新复制表。提交记录不会丢失。',
      okText: '确认重置',
      cancelText: '取消',
      onOk: async () => {
        try {
          const sandbox = await resetLab(code)
          setResult(null)
          setHint(null)
          setLab((current) => (current ? { ...current, sandboxSchema: sandbox.schemaName, sandboxActive: true } : current))
          message.success('沙箱已重置')
        } catch {
          message.error('重置失败')
        }
      }
    })
  }

  const handleSubmit = async () => {
    if (!sql.trim()) {
      message.warning('请先编写 SQL')
      return
    }
    setSubmitting(true)
    try {
      const submission = await submitLab(code, sql)
      setResult(submission)
      if (submission.passed) message.success(`任务通过，得分 ${submission.score}`)
      else message.warning(`本次得分 ${submission.score}，请查看反馈`)
      await refresh()
    } catch {
      message.error('提交失败，请检查 SQL 或稍后重试')
    } finally {
      setSubmitting(false)
    }
  }

  const handleHint = async () => {
    try {
      const next = await requestHint(code)
      setHint(next)
    } catch {
      message.error('提示获取失败')
    }
  }

  const openTutor = async () => {
    setTutorOpen(true)
    try {
      setTutorMessages(await getTutorHistory(code))
    } catch {
      // 历史为空不影响新对话
    }
  }

  const sendTutor = async () => {
    const content = tutorInput.trim()
    if (!content) return
    setTutorLoading(true)
    try {
      const chat = await chatWithTutor(code, content)
      setTutorMessages(chat.history)
      setTutorModel(chat.fallback ? '规则导师（未配置大模型）' : chat.model)
      setTutorInput('')
    } catch {
      message.error('AI 导师暂时不可用')
    } finally {
      setTutorLoading(false)
    }
  }

  const sampleColumns = useMemo(() => {
    const columns = result?.result?.sampleColumns ?? []
    return columns.map((column, index) => ({
      title: column,
      dataIndex: String(index),
      key: String(index),
      ellipsis: true
    }))
  }, [result])

  const sampleData = useMemo(() => {
    const rows = result?.result?.sampleRows ?? []
    return rows.map((row, index) => {
      const record: Record<string, string> = { key: String(index) }
      row.forEach((cell, cellIndex) => { record[String(cellIndex)] = cell ?? 'NULL' })
      return record
    })
  }, [result])

  if (loading && !lab) {
    return <Skeleton active paragraph={{ rows: 12 }} />
  }
  if (loadError && !lab) {
    return <ErrorState title="任务加载失败" description={loadError} onRetry={() => void refresh()} />
  }
  if (!lab) {
    return <Empty description="任务不存在或尚未发布" />
  }

  return (
    <>
      <PageHeader
        title={lab.title}
        description={lab.summary}
        extra={
          <Space>
            <Button icon={<ArrowLeftOutlined />} onClick={() => navigate('/learning')}>返回路径</Button>
            <Button icon={<ReloadOutlined />} onClick={() => void refresh()}>刷新</Button>
          </Space>
        }
        meta={
          <Space wrap>
            <Tag>{lab.level}</Tag>
            <Tag>{lab.difficulty}</Tag>
            {lab.taskType === 'SQL_WRITE' && <Tag color="orange">写任务</Tag>}
            <Tag>{lab.estimatedMinutes} 分钟</Tag>
            <Tag>通过线 {lab.passScore}</Tag>
            {lab.sandboxActive
              ? <StatusBadge tone="success" dot={false}>沙箱 {lab.sandboxSchema}</StatusBadge>
              : <StatusBadge tone="warning" dot={false}>沙箱未创建</StatusBadge>}
            {lab.level === 'L6' && <Tag>Medium 沙箱（并发受限）</Tag>}
          </Space>
        }
      />

      {/* align="top"：DataSurface 自带 height:100%，两栏并排时不限制会被拉成等高。 */}
      <Row gutter={16} align="top">
        <Col span={10}>
          <DataSurface title="任务卡" label="任务卡">
            <Typography.Paragraph><strong>业务场景</strong></Typography.Paragraph>
            <Typography.Paragraph type="secondary">{lab.scenario}</Typography.Paragraph>
            <Typography.Paragraph><strong>验收标准</strong></Typography.Paragraph>
            <Typography.Paragraph type="secondary">{lab.objective}</Typography.Paragraph>
            <Divider style={{ margin: '12px 0' }} />
            <Steps
              direction="vertical"
              size="small"
              items={lab.steps.map((step) => ({ title: step.title, description: step.detail }))}
            />
            <Divider style={{ margin: '12px 0' }} />
            <Typography.Paragraph style={{ marginBottom: 8 }}><strong>知识点</strong></Typography.Paragraph>
            <ul className="learn-knowledge">
              {(lab.knowledge ?? []).map((item) => (
                <li key={item.title}>
                  <strong>{item.title}</strong>
                  <span>{item.detail}</span>
                </li>
              ))}
            </ul>
            <Divider style={{ margin: '12px 0' }} />
            <Descriptions size="small" column={1}>
              <Descriptions.Item label="数据表">
                {lab.tableRefs.map((table) => <Tag key={table}>{table}</Tag>)}
              </Descriptions.Item>
              {lab.datasets?.length ? (
                <Descriptions.Item label="样例数据">
                  <Space size={4} wrap>
                    {lab.datasets.map((dataset) => (
                      <Tag key={`${dataset.code}@${dataset.version}`}>
                        {dataset.code}@{dataset.version} · {dataset.license} · {dataset.rowCount} 行
                        {dataset.piiReviewed ? ' · 已复核 PII' : ''}
                      </Tag>
                    ))}
                  </Space>
                </Descriptions.Item>
              ) : lab.datasetRefs?.length ? (
                <Descriptions.Item label="样例数据">
                  {lab.datasetRefs.map((ref) => <Tag key={ref}>{ref}</Tag>)}
                </Descriptions.Item>
              ) : null}
              <Descriptions.Item label="评分维度">
                {lab.rubric.map((item) => <Tag key={item.key}>{item.name} {item.weight}%</Tag>)}
              </Descriptions.Item>
              {lab.relatedFeatures?.length ? (
                <Descriptions.Item label="对应系统功能">
                  <span className="learn-meta-features">{lab.relatedFeatures.join(' · ')}</span>
                </Descriptions.Item>
              ) : null}
              <Descriptions.Item label="课程版本">v{lab.version}</Descriptions.Item>
            </Descriptions>
          </DataSurface>

          {hint && (
            <DataSurface
              title={<Space><BulbOutlined />{hint.title}</Space>}
              label="提示"
              style={{ marginTop: 16 }}
            >
              <Typography.Paragraph style={{ marginBottom: 0 }}>{hint.message}</Typography.Paragraph>
              {hint.lastHint && <Typography.Text type="secondary">提示额度已用完，后续请依靠自查清单。</Typography.Text>}
            </DataSurface>
          )}
        </Col>

        <Col span={14}>
          <DataSurface
            title="SQL 工作区"
            extra={<span className="learn-panel-note">{lab.taskType === 'SQL_WRITE' ? '写沙箱 · 单条语句' : '只读沙箱 · 单条语句'}</span>}
            label="SQL 工作区"
          >
            <Alert
              type={lab.taskType === 'SQL_WRITE' ? 'warning' : 'info'}
              showIcon
              style={{ marginBottom: 12 }}
              message={lab.taskType === 'SQL_WRITE'
                ? '写任务：只允许单条 INSERT / UPDATE / DELETE，且不要写 schema 前缀；判分后目标表会重置为教学初始数据。'
                : '在沙箱 schema 中直接查询 orders_raw / order_status_dict，不要写 schema 前缀；提交后由服务端在独立只读事务中执行并比对标准结果。'}
            />
            <Input.TextArea
              value={sql}
              onChange={(event) => setSql(event.target.value)}
              rows={14}
              spellCheck={false}
              style={{ fontFamily: 'var(--font-mono)', fontSize: 'var(--font-body)' }}
              placeholder="-- 从 SELECT 开始，逐步加入过滤、去重或聚合"
            />
            <Space style={{ marginTop: 12 }} wrap>
              {!lab.sandboxActive && (
                <Button type="primary" icon={<PlayCircleOutlined />} loading={starting} onClick={() => void handleStart()}>
                  开始实验
                </Button>
              )}
              <Button type="primary" icon={<SendOutlined />} loading={submitting} disabled={!sql.trim()} onClick={() => void handleSubmit()}>
                提交判分
              </Button>
              <Button icon={<BulbOutlined />} onClick={() => void handleHint()}>获取提示</Button>
              <Button icon={<RobotOutlined />} onClick={() => void openTutor()}>AI 导师</Button>
              <Button danger icon={<ReloadOutlined />} onClick={handleReset}>重置沙箱</Button>
              {lab.bestScore !== undefined && <Typography.Text type="secondary">历史最高 {lab.bestScore} 分</Typography.Text>}
            </Space>
          </DataSurface>

          {result?.result && (
            <DataSurface title="判分结果" label="判分结果" style={{ marginTop: 16 }}>
              <Space direction="vertical" style={{ width: '100%' }} size={12}>
                <Alert
                  type={result.result.passed ? 'success' : 'warning'}
                  showIcon
                  message={`得分 ${result.result.score} · ${result.result.passed ? '已通过' : '未通过'}`}
                  description={result.result.summary}
                />
                <List
                  size="small"
                  dataSource={result.result.items}
                  renderItem={(item) => (
                    <List.Item>
                      <Space align="start">
                        {item.passed
                          ? <CheckCircleFilled style={{ color: 'var(--success)', marginTop: 3 }} />
                          : <CloseCircleFilled style={{ color: 'var(--danger)', marginTop: 3 }} />}
                        <Space direction="vertical" size={0}>
                          <span>{item.name}（权重 {item.weight}%）</span>
                          <Typography.Text type="secondary">{item.detail}</Typography.Text>
                        </Space>
                      </Space>
                    </List.Item>
                  )}
                />
                {result.result.sampleRows.length > 0 && (
                  <>
                    <Typography.Text strong>你的结果样例</Typography.Text>
                    <Table
                      size="small"
                      columns={sampleColumns}
                      dataSource={sampleData}
                      pagination={false}
                      scroll={{ x: 'max-content' }}
                    />
                  </>
                )}
                {result.result.hints.length > 0 && (
                  <Alert type="info" showIcon message="下一步建议" description={
                    <ul style={{ margin: 0, paddingLeft: 18 }}>
                      {result.result.hints.map((item) => <li key={item}>{item}</li>)}
                    </ul>
                  } />
                )}
                {result.gradedAt && (
                  <Typography.Text type="secondary">提交时间：{formatDateTime(result.gradedAt)}</Typography.Text>
                )}
              </Space>
            </DataSurface>
          )}

          <DataSurface title="提交时间线" label="提交时间线" style={{ marginTop: 16 }}>
            {submissions.length === 0 ? <Typography.Text type="secondary">还没有提交记录。</Typography.Text> : (
              <List
                size="small"
                dataSource={submissions}
                renderItem={(item) => (
                  <List.Item>
                    <Space>
                      <Tag>第 {item.attempt} 次</Tag>
                      <span className="learn-lab-score">得分 {item.score ?? '—'}</span>
                      <StatusBadge tone={item.passed ? 'success' : 'warning'} dot={false}>
                        {item.passed ? '通过' : item.status}
                      </StatusBadge>
                    </Space>
                  </List.Item>
                )}
              />
            )}
          </DataSurface>
        </Col>
      </Row>

      <Drawer
        title={<Space><RobotOutlined />AI 导师</Space>}
        width={460}
        open={tutorOpen}
        onClose={() => setTutorOpen(false)}
        extra={tutorModel ? <Tag>{tutorModel}</Tag> : null}
      >
        <Space direction="vertical" style={{ width: '100%' }} size={12}>
          <Alert type="info" showIcon message="导师只给提示，不直接给可提交答案；对话会被记录用于教学复盘。" />
          <div style={{ maxHeight: 440, overflow: 'auto' }}>
            {tutorMessages.length === 0 ? <Empty description="问一个具体问题，例如“为什么我的结果行数偏多？”" /> : (
              <Space direction="vertical" style={{ width: '100%' }} size={10}>
                {tutorMessages.map((item, index) => (
                  <div key={`${item.role}-${index}`} className={`tutor-bubble is-${item.role}`}>
                    <Typography.Text type="secondary" style={{ fontSize: 'var(--font-table)' }}>
                      {item.role === 'user' ? '我' : item.model || '导师'}
                    </Typography.Text>
                    <div style={{ whiteSpace: 'pre-wrap' }}>{item.content}</div>
                  </div>
                ))}
              </Space>
            )}
          </div>
          <Input.TextArea
            rows={3}
            value={tutorInput}
            onChange={(event) => setTutorInput(event.target.value)}
            placeholder="描述你卡住的地方，或粘贴报错信息"
          />
          <Button type="primary" icon={<SendOutlined />} loading={tutorLoading} onClick={() => void sendTutor()}>
            发送
          </Button>
        </Space>
      </Drawer>
    </>
  )
}
