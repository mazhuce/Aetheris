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
      }]
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
  })
})
