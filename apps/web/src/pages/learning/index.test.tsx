import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { App as AntdApp } from 'antd'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningCenterPage from './index'

vi.mock('@/api/learning', () => ({
  listLearningPaths: vi.fn(),
  getProgress: vi.fn(),
  listProjects: vi.fn(),
  getLearningPath: vi.fn()
}))

function page() {
  return render(
    <MemoryRouter>
      <AntdApp><LearningCenterPage /></AntdApp>
    </MemoryRouter>
  )
}

describe('learning center request state', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows a retryable error instead of an empty path list when loading fails', async () => {
    vi.mocked(api.listLearningPaths).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.getProgress).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listProjects).mockRejectedValue(new Error('learning service down'))
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('学习数据加载失败')
    fireEvent.click(screen.getByRole('button', { name: /重新加载/ }))
    await waitFor(() => expect(api.listLearningPaths).toHaveBeenCalledTimes(2))
  })
  it('renders the active path and progress from the learning service', async () => {
    vi.mocked(api.listLearningPaths).mockResolvedValue([{
      code: 'da-basic', title: '数据分析师入门', description: '从指标口径到趋势分析',
      targetRole: '数据分析师', level: 'L1-L2', version: 1, estimatedHours: 36,
      labCount: 6, completedLabs: 1, progressPercent: 17
    }])
    vi.mocked(api.getProgress).mockResolvedValue({
      completed: 1, total: 6, percent: 17, averageScore: 90, skills: [], credentials: []
    })
    vi.mocked(api.listProjects).mockResolvedValue([])
    vi.mocked(api.getLearningPath).mockResolvedValue({
      code: 'da-basic', title: '数据分析师入门', description: '从指标口径到趋势分析',
      targetRole: '数据分析师', level: 'L1-L2', estimatedHours: 36, modules: [], skills: []
    })
    page()

    expect(await screen.findByText('数据分析师入门')).toBeInTheDocument()
    expect(screen.getByText('1 / 6')).toBeInTheDocument()
  })
})
