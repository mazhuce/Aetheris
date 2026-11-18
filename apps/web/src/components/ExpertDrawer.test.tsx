import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/ai'
import * as learning from '@/api/learning'
import ExpertDrawer from './ExpertDrawer'

vi.mock('@/api/ai', () => ({ createConversation: vi.fn(), sendChat: vi.fn() }))
vi.mock('@/api/learning', () => ({ getLab: vi.fn(), trackLearningEvent: vi.fn() }))

function page() {
  return render(
    <MemoryRouter initialEntries={['/learning/labs/l1-sales']}>
      <ExpertDrawer open onClose={() => undefined} />
    </MemoryRouter>
  )
}

const placeholder = '问 SQL、数仓分层、报错或学习路径…'

describe('data expert drawer', () => {
  beforeEach(() => vi.clearAllMocks())

  it('sends expert mode with learning context and renders the reply', async () => {
    vi.mocked(api.createConversation).mockResolvedValue({ id: 5, title: '新对话' })
    vi.mocked(api.sendChat).mockResolvedValue({ role: 'assistant', content: '先检查分区条件。' })
    vi.mocked(learning.getLab).mockResolvedValue({
      code: 'l1-sales', title: '销售查询', level: 'L1', difficulty: 'easy', taskType: 'SQL',
      steps: [], tableRefs: [], passScore: 60, estimatedMinutes: 15, version: 1, rubric: [],
      sandboxActive: false, passed: false, lastStatus: 'graded', objective: '只输出有效订单的金额'
    })
    page()

    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '这条 SQL 为什么慢' } })
    fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }))

    expect(await screen.findByText('先检查分区条件。')).toBeInTheDocument()
    expect(api.sendChat).toHaveBeenCalledWith(
      5,
      expect.any(Array),
      false,
      { expert: true, context: expect.stringContaining('任务验收标准：只输出有效订单的金额') }
    )
    expect(learning.trackLearningEvent).toHaveBeenCalledWith('expert_question_asked', expect.any(Object))
  })

  it('shows an offline hint without faking a model answer when the model is unavailable', async () => {
    vi.mocked(api.createConversation).mockResolvedValue({ id: 6, title: '新对话' })
    vi.mocked(api.sendChat).mockRejectedValue({ response: { status: 503 } })
    page()

    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '数仓分层怎么选' } })
    fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }))

    expect(await screen.findByText('离线提示（非模型回答）')).toBeInTheDocument()
    expect(learning.trackLearningEvent).toHaveBeenCalledWith('expert_fallback_used', expect.any(Object))
    expect(screen.getByText(/模型服务未配置密钥或暂不可用/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
    await waitFor(() => expect(api.sendChat).toHaveBeenCalledTimes(1))

    vi.mocked(api.sendChat).mockResolvedValue({ role: 'assistant', content: '重试成功后的回答' })
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByText('重试成功后的回答')).toBeInTheDocument()
    await waitFor(() => expect(api.sendChat).toHaveBeenCalledTimes(2))
  })
  it('surfaces the model balance warning instead of a generic outage', async () => {
    vi.mocked(api.createConversation).mockResolvedValue({ id: 8, title: '新对话' })
    vi.mocked(api.sendChat).mockRejectedValue({
      response: { status: 503, data: { content: '模型账户余额不足，请充值后重试' } }
    })
    page()

    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '现在还能用吗' } })
    fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }))

    expect(await screen.findByText(/模型账户余额不足/)).toBeInTheDocument()
    expect(screen.queryByText(/模型服务未配置密钥或暂不可用/)).not.toBeInTheDocument()
  })
  it('locks duplicate submissions while the expert request is in flight', async () => {
    let resolve!: (value: api.ChatResult) => void
    vi.mocked(api.createConversation).mockResolvedValue({ id: 7, title: '新对话' })
    vi.mocked(api.sendChat).mockReturnValue(new Promise<api.ChatResult>((done) => { resolve = done }))
    vi.mocked(learning.getLab).mockRejectedValue(new Error('not a lab page'))
    page()

    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '重复提问' } })
    const send = screen.getByRole('button', { name: /发\s*送/ })
    fireEvent.click(send)
    fireEvent.click(send)
    await waitFor(() => expect(api.sendChat).toHaveBeenCalledTimes(1))

    await act(async () => resolve({ role: 'assistant', content: '一次回答' }))
    expect(await screen.findByText('一次回答')).toBeInTheDocument()
  })
})