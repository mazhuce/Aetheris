import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/ai'
import * as learning from '@/api/learning'
import ExpertDrawer from './ExpertDrawer'

vi.mock('@/api/ai', () => ({ createConversation: vi.fn(), sendChat: vi.fn() }))
vi.mock('@/api/learning', () => ({ getLab: vi.fn() }))

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
  })

  it('shows an offline hint without faking a model answer when the model is unavailable', async () => {
    vi.mocked(api.createConversation).mockResolvedValue({ id: 6, title: '新对话' })
    vi.mocked(api.sendChat).mockRejectedValue({ response: { status: 503 } })
    page()

    fireEvent.change(screen.getByPlaceholderText(placeholder), { target: { value: '数仓分层怎么选' } })
    fireEvent.click(screen.getByRole('button', { name: /发\s*送/ }))

    expect(await screen.findByText('离线提示（非模型回答）')).toBeInTheDocument()
    expect(screen.getByText(/模型服务未配置密钥或暂不可用/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /重\s*试/ })).toBeInTheDocument()
    await waitFor(() => expect(api.sendChat).toHaveBeenCalledTimes(1))

    vi.mocked(api.sendChat).mockResolvedValue({ role: 'assistant', content: '重试成功后的回答' })
    fireEvent.click(screen.getByRole('button', { name: /重\s*试/ }))
    expect(await screen.findByText('重试成功后的回答')).toBeInTheDocument()
    await waitFor(() => expect(api.sendChat).toHaveBeenCalledTimes(2))
  })
})