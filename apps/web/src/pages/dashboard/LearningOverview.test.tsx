import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import LearningOverview from './LearningOverview'
import {
  getLearningPath,
  getProgress,
  listLearningPaths,
  listProjects
} from '@/api/learning'

vi.mock('@/api/learning', () => ({
  listLearningPaths: vi.fn(),
  getProgress: vi.fn(),
  listProjects: vi.fn(),
  getLearningPath: vi.fn()
}))

const mocked = {
  listLearningPaths: vi.mocked(listLearningPaths),
  getProgress: vi.mocked(getProgress),
  listProjects: vi.mocked(listProjects),
  getLearningPath: vi.mocked(getLearningPath)
}

describe('LearningOverview', () => {
  beforeEach(() => {
    localStorage.clear()
    localStorage.setItem('aetheris.learning.activePath', 'de-basic')
    mocked.listLearningPaths.mockResolvedValue([{
      code: 'de-basic', title: '数据开发工程师入门', level: 'L1', version: 1,
      estimatedHours: 8, labCount: 15, completedLabs: 6, progressPercent: 40
    }])
    mocked.getProgress.mockResolvedValue({
      completed: 6, total: 15, percent: 40, averageScore: 88, skills: [],
      credentials: [{ id: 1, type: 'PATH_COMPLETION', title: '结业证明', issuedAt: '2026-01-01' }]
    })
    mocked.listProjects.mockResolvedValue([])
    mocked.getLearningPath.mockResolvedValue({
      code: 'de-basic', title: '数据开发工程师入门', level: 'L1', estimatedHours: 8, skills: [],
      modules: [{
        code: 'de-m1', title: 'L1 · SQL 基础', level: 'L1',
        labs: [
          { code: 'de-l1-01', title: 'SQL 基础', level: 'L1', difficulty: 'easy', estimatedMinutes: 20, orderNo: 1, status: 'passed', passed: true, bestScore: 100 },
          { code: 'de-l1-02', title: '下一步聚合', level: 'L1', difficulty: 'easy', estimatedMinutes: 20, orderNo: 2, status: 'not_started', passed: false }
        ]
      }]
    })
  })

  it('展示任务进度、凭证与下一题入口', async () => {
    render(<MemoryRouter><LearningOverview /></MemoryRouter>)

    expect(await screen.findByText('我的学习')).toBeInTheDocument()
    expect(screen.getByText('6/15')).toBeInTheDocument()
    expect(screen.getByText('88')).toBeInTheDocument()
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('下一题：下一步聚合')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /继续学习/ })).toBeInTheDocument()
  })
})