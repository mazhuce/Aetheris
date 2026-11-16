import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import * as api from '@/api/learning'
import LearningPortfolioPage from './portfolio'

vi.mock('@/api/learning', () => ({ getProgress: vi.fn(), listProjects: vi.fn() }))
vi.mock('@/hooks/useAuthSession', () => ({
  useAuthSession: () => ({ status: 'authenticated', user: { username: 'alice', role: 'viewer' } })
}))

const progress: api.ProgressView = {
  completed: 3,
  total: 8,
  percent: 38,
  averageScore: 86,
  skills: [
    { code: 'sql-basics', title: 'SQL 基础', level: 'L1', knowledgePoints: ['SELECT'], labs: 2, passedLabs: 2, bestScore: 92 }
  ],
  credentials: [
    { id: 11, type: 'path-completion', title: 'SQL 数据分析入门', issuedAt: '2026-11-10T08:00:00Z' }
  ]
}

const projects: api.ProjectView[] = [
  {
    code: 'proj-order',
    title: '订单分析项目',
    summary: '从明细到指标',
    level: 'L3',
    deliverables: ['结业看板', '口径说明'],
    labCount: 4,
    passedLabs: 2
  }
]

function page() {
  return render(
    <MemoryRouter>
      <LearningPortfolioPage />
    </MemoryRouter>
  )
}

describe('learning portfolio page', () => {
  beforeEach(() => vi.clearAllMocks())

  it('renders real progress, skills, projects and credentials', async () => {
    vi.mocked(api.getProgress).mockResolvedValue(progress)
    vi.mocked(api.listProjects).mockResolvedValue(projects)
    page()

    expect(await screen.findByText('SQL 基础')).toBeInTheDocument()
    expect(screen.getByText('3/8')).toBeInTheDocument()
    expect(screen.getByText('订单分析项目')).toBeInTheDocument()
    expect(screen.getByText('SQL 数据分析入门')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /导出证据包/ })).toBeEnabled()
  })

  it('shows a retryable error state when loading fails', async () => {
    vi.mocked(api.getProgress).mockRejectedValue(new Error('learning service down'))
    vi.mocked(api.listProjects).mockResolvedValue([])
    page()

    expect(await screen.findByRole('alert')).toHaveTextContent('求职证据加载失败')
    expect(screen.getByRole('button', { name: /重新加载/ })).toBeInTheDocument()
  })
})