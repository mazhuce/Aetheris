import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningLabPage from './lab'

vi.mock('@/api/learning', () => ({
  chatWithTutor: vi.fn(),
  getLab: vi.fn(),
  getTutorHistory: vi.fn(),
  listSubmissions: vi.fn(),
  requestHint: vi.fn(),
  resetLab: vi.fn(),
  startLab: vi.fn(),
  submitLab: vi.fn()
}))

function page() {
  return render(
    <MemoryRouter initialEntries={['/learning/labs/l1-sales']}>
      <AntdApp>
        <Routes>
          <Route path="/learning/labs/:code" element={<LearningLabPage />} />
        </Routes>
      </AntdApp>
    </MemoryRouter>
  )
}

const baseLab: api.LabDetail = {
  code: 'l1-sales', title: '销售查询', level: 'L1', difficulty: 'easy', taskType: 'SQL',
  summary: '统计有效订单', objective: '只输出有效订单的金额',
  steps: [], tableRefs: ['teach.orders_raw'], starterSql: 'SELECT order_id FROM orders_raw',
  passScore: 60, estimatedMinutes: 15, version: 1, rubric: [],
  sandboxSchema: 'sb_u1', sandboxActive: true, passed: false, lastStatus: 'not_started',
  datasetRefs: ['teach.orders_raw@1'],
  datasets: [{
    code: 'teach.orders_raw', version: 1, tableName: 'teach.orders_raw', rowCount: 26,
    license: 'internal-training', source: 'synthetic', piiReviewed: true
  }]
}

describe('learning lab request state', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a retryable error instead of "task not found" when loading fails', async () => {
    vi.mocked(api.getLab).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listSubmissions).mockRejectedValue(new Error('learning service down'))
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('任务加载失败')
    expect(screen.queryByText('任务不存在或尚未发布')).not.toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }))
    await waitFor(() => expect(api.getLab).toHaveBeenCalledTimes(2))
  })
  it('explains a missing lab from the service message', async () => {
    const missing = Object.assign(new Error('bad request'), {
      response: { status: 400, data: { message: '实验任务不存在：not-a-lab' } }
    })
    vi.mocked(api.getLab).mockRejectedValue(missing)
    vi.mocked(api.listSubmissions).mockRejectedValue(missing)
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('任务不存在或已下线')
  })
  it('shows the graded result after submitting the starter SQL', async () => {
    vi.mocked(api.getLab).mockResolvedValue({
      code: 'l1-sales', title: '销售查询', level: 'L1', difficulty: 'easy', taskType: 'SQL',
      summary: '统计有效订单', objective: '只输出有效订单的金额',
      steps: [], tableRefs: ['teach.orders_raw'], starterSql: 'SELECT order_id FROM orders_raw',
      passScore: 60, estimatedMinutes: 15, version: 1, rubric: [],
      sandboxSchema: 'sb_u1', sandboxActive: true, passed: false, lastStatus: 'not_started',
      datasetRefs: ['teach.orders_raw@1'],
      datasets: [{
        code: 'teach.orders_raw', version: 1, tableName: 'teach.orders_raw', rowCount: 26,
        license: 'internal-training', source: 'synthetic', piiReviewed: true
      }],
      nextLabCode: 'l1-sales-2', nextLabTitle: '热销品类'
    })
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    vi.mocked(api.submitLab).mockResolvedValue({
      id: 1, labCode: 'l1-sales', attempt: 1, status: 'graded', score: 86, passed: true,
      gradedAt: '2026-11-17T10:00:00Z',
      result: { score: 86, passed: true, summary: '结果与标准答案一致', items: [],
        sampleColumns: [], sampleRows: [], hints: [] }
    })
    page()

    expect(await screen.findByText('销售查询')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: /提交判分/ }))

    expect(await screen.findByText(/得分 86 · 已通过/)).toBeInTheDocument()
    expect(screen.getByText('结果与标准答案一致')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /下一题：热销品类/ })).toBeInTheDocument()
  })
  it('offers a path return when the last lab passes', async () => {
    vi.mocked(api.getLab).mockResolvedValue({ ...baseLab, passed: false })
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    vi.mocked(api.submitLab).mockResolvedValue({
      id: 2, labCode: 'l1-sales', attempt: 1, status: 'graded', score: 100, passed: true,
      gradedAt: '2026-11-17T10:05:00Z',
      result: { score: 100, passed: true, summary: '结果与标准答案一致', items: [],
        sampleColumns: [], sampleRows: [], hints: [] }
    })
    page()

    fireEvent.click(await screen.findByRole('button', { name: /提交判分/ }))

    expect(await screen.findByRole('button', { name: '返回学习路径' })).toBeInTheDocument()
  })
  it('renders the rule hint returned by the service', async () => {
    vi.mocked(api.getLab).mockResolvedValue(baseLab)
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    vi.mocked(api.requestHint).mockResolvedValue({ level: 1, title: '方向提示', message: '先确认过滤条件', lastHint: false })
    page()

    fireEvent.click(await screen.findByRole('button', { name: /获取提示/ }))
    expect(await screen.findByText('先确认过滤条件')).toBeInTheDocument()
  })

  it('marks the tutor reply as rule-based when the model is unavailable', async () => {
    vi.mocked(api.getLab).mockResolvedValue(baseLab)
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    vi.mocked(api.getTutorHistory).mockResolvedValue([])
    vi.mocked(api.chatWithTutor).mockResolvedValue({
      reply: '规则导师回答', model: 'rule-based', fallback: true,
      history: [
        { role: 'user', content: '为什么行数偏多' },
        { role: 'assistant', content: '规则导师回答', model: 'rule-based' }
      ]
    })
    page()

    fireEvent.click(await screen.findByRole('button', { name: /AI 导师/ }))
    fireEvent.change(screen.getByPlaceholderText('描述你卡住的地方，或粘贴报错信息'), { target: { value: '为什么行数偏多' } })
    fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }))

    expect(await screen.findByText('规则导师回答')).toBeInTheDocument()
    expect(screen.getByText('规则导师（未配置大模型）')).toBeInTheDocument()
  })
  it('shows grading hints when the submission does not pass', async () => {
    vi.mocked(api.getLab).mockResolvedValue(baseLab)
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    vi.mocked(api.submitLab).mockResolvedValue({
      id: 2, labCode: 'l1-sales', attempt: 1, status: 'graded', score: 40, passed: false,
      gradedAt: '2026-11-17T10:00:00Z',
      result: { score: 40, passed: false, summary: '结果与标准答案不一致', items: [],
        sampleColumns: [], sampleRows: [], hints: ['先确认过滤条件'] }
    })
    page()

    fireEvent.click(await screen.findByRole('button', { name: /提交判分/ }))

    expect(await screen.findByText(/得分 40 · 未通过/)).toBeInTheDocument()
    expect(screen.getByText('先确认过滤条件')).toBeInTheDocument()
  })
  it('marks write tasks and shows the reset warning', async () => {
    vi.mocked(api.getLab).mockResolvedValue({ ...baseLab, code: 'de-l2-08', taskType: 'SQL_WRITE', title: '写任务示例' })
    vi.mocked(api.listSubmissions).mockResolvedValue([])
    page()

    expect(await screen.findByText('写任务示例')).toBeInTheDocument()
    expect(screen.getByText('写任务')).toBeInTheDocument()
    expect(screen.getByText(/写任务：只允许单条 INSERT \/ UPDATE \/ DELETE/)).toBeInTheDocument()
  })
})