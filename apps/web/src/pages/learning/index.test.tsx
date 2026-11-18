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
  it('switches the active path and reloads its detail', async () => {
    vi.mocked(api.listLearningPaths).mockResolvedValue([
      { code: 'da-basic', title: '数据分析师入门', level: 'L1-L2', version: 1, estimatedHours: 36,
        labCount: 6, completedLabs: 0, progressPercent: 0 },
      { code: 'de-basic', title: '数据开发工程师入门', level: 'L1-L2', version: 1, estimatedHours: 52,
        labCount: 14, completedLabs: 0, progressPercent: 0 }
    ])
    vi.mocked(api.getProgress).mockResolvedValue({ completed: 0, total: 20, percent: 0, averageScore: 0, skills: [], credentials: [] })
    vi.mocked(api.listProjects).mockResolvedValue([])
    vi.mocked(api.getLearningPath).mockImplementation(async (code: string) => ({
      code, title: code === 'da-basic' ? '数据分析师入门' : '数据开发工程师入门',
      description: '', level: 'L1-L2', estimatedHours: 36, modules: [], skills: []
    }))
    page()

    await screen.findByText('数据分析师入门')
    fireEvent.click(screen.getByText('数据开发工程师入门 · L1-L2'))
    await waitFor(() => expect(api.getLearningPath).toHaveBeenCalledWith('de-basic'))
  })
  it('remembers the last selected path across visits', async () => {
    localStorage.setItem('aetheris.learning.activePath', 'de-basic')
    vi.mocked(api.listLearningPaths).mockResolvedValue([
      { code: 'da-basic', title: '数据分析师入门', level: 'L1-L2', version: 1, estimatedHours: 36,
        labCount: 8, completedLabs: 0, progressPercent: 0 },
      { code: 'de-basic', title: '数据开发工程师入门', level: 'L1-L2', version: 1, estimatedHours: 52,
        labCount: 15, completedLabs: 0, progressPercent: 0 }
    ])
    vi.mocked(api.getProgress).mockResolvedValue({ completed: 0, total: 23, percent: 0, averageScore: 0, skills: [], credentials: [] })
    vi.mocked(api.listProjects).mockResolvedValue([])
    vi.mocked(api.getLearningPath).mockImplementation(async (code: string) => ({
      code, title: code === 'da-basic' ? '数据分析师入门' : '数据开发工程师入门',
      description: '', level: 'L1-L2', estimatedHours: 36, modules: [], skills: []
    }))
    page()

    await screen.findByText('数据开发工程师入门')
    await waitFor(() => expect(api.getLearningPath).toHaveBeenCalledWith('de-basic'))

    fireEvent.click(screen.getByText('数据分析师入门 · L1-L2'))
    await waitFor(() => expect(localStorage.getItem('aetheris.learning.activePath')).toBe('da-basic'))
  })
  it('filters labs by progress state', async () => {
    vi.mocked(api.listLearningPaths).mockResolvedValue([
      { code: 'de-basic', title: '数据开发工程师入门', level: 'L1-L2', version: 1, estimatedHours: 52,
        labCount: 2, completedLabs: 1, progressPercent: 50 }
    ])
    vi.mocked(api.getProgress).mockResolvedValue({ completed: 1, total: 2, percent: 50, averageScore: 100, skills: [], credentials: [] })
    vi.mocked(api.listProjects).mockResolvedValue([])
    vi.mocked(api.getLearningPath).mockResolvedValue({
      code: 'de-basic', title: '数据开发工程师入门', description: '', level: 'L1-L2', estimatedHours: 52, skills: [],
      modules: [{
        code: 'de-m1', title: 'L1 · 基础', level: 'L1', labs: [
          { code: 'de-1', title: '任务甲（已通过）', level: 'L1', difficulty: 'easy', estimatedMinutes: 15, orderNo: 1, status: 'passed', bestScore: 100, passed: true },
          { code: 'de-2', title: '任务乙（未开始）', level: 'L1', difficulty: 'easy', estimatedMinutes: 15, orderNo: 2, status: 'not_started', passed: false }
        ]
      }]
    })
    page()

    expect(await screen.findByText('任务甲（已通过）')).toBeInTheDocument()
    expect(screen.getByText('任务乙（未开始）')).toBeInTheDocument()

    fireEvent.click(screen.getByText('未完成'))
    await waitFor(() => expect(screen.queryByText('任务甲（已通过）')).not.toBeInTheDocument())
    expect(screen.getByText('任务乙（未开始）')).toBeInTheDocument()

    fireEvent.click(screen.getByText('已通过'))
    await waitFor(() => expect(screen.queryByText('任务乙（未开始）')).not.toBeInTheDocument())
    expect(screen.getByText('任务甲（已通过）')).toBeInTheDocument()
  })
})
